/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.discovery;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fans out a single {@link ComplexSource} to multiple subscriber sources.
 *
 * <p>sdrtrunk sources are single-consumer. The {@code ComplexSampleFanout} owns one real
 * {@link ComplexSource}, registers itself as the sole listener on it, and re-broadcasts
 * each {@link ComplexSamples} buffer and relevant {@link SourceEvent}s to every registered
 * subscriber via thin {@link SubscriberSource} adapters.  This allows multiple probe
 * chains to share one tuner channel allocation.</p>
 *
 * <h3>Usage pattern:</h3>
 * <pre>
 *   ComplexSampleFanout fanout = new ComplexSampleFanout(realSource);
 *   ComplexSource subA = fanout.newSubscriberSource();
 *   ComplexSource subB = fanout.newSubscriberSource();
 *   chainA.setSource(subA); chainA.start();
 *   chainB.setSource(subB); chainB.start();
 *   // ... samples flow to both chains ...
 *   fanout.stop(); // stops the underlying real source; subscribers stop receiving
 * </pre>
 */
public class ComplexSampleFanout
{
    private static final Logger mLog = LoggerFactory.getLogger(ComplexSampleFanout.class);

    private final ComplexSource mRealSource;
    private final CopyOnWriteArrayList<SubscriberSource> mSubscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean mStopped = new AtomicBoolean(false);

    /**
     * Constructs a fanout over the given real source and immediately registers as its listener.
     *
     * @param realSource the underlying tuner channel source; must be a {@link ComplexSource}
     */
    public ComplexSampleFanout(ComplexSource realSource)
    {
        mRealSource = realSource;

        // Register ourselves as the sole ComplexSamples consumer on the real source
        mRealSource.setListener(this::onComplexSamples);

        // Register ourselves as the source-event consumer so we can forward events to subscribers
        mRealSource.setSourceEventListener(this::onSourceEvent);
    }

    /**
     * Creates and returns a new subscriber source backed by this fanout.
     * The subscriber will receive all {@link ComplexSamples} buffers and forwarded
     * {@link SourceEvent}s delivered after it is registered.
     *
     * <p>Pass the returned source to {@link io.github.dsheirer.module.ProcessingChain#setSource}.</p>
     *
     * @return a new {@link ComplexSource} subscriber
     */
    public ComplexSource newSubscriberSource()
    {
        SubscriberSource sub = new SubscriberSource(mRealSource.getSampleRate(), mRealSource.getFrequency());
        mSubscribers.add(sub);
        return sub;
    }

    /**
     * Removes a previously created subscriber from receiving further buffers or events.
     *
     * @param subscriberSource a source previously returned by {@link #newSubscriberSource()}
     */
    public void removeSubscriberSource(ComplexSource subscriberSource)
    {
        mSubscribers.remove(subscriberSource);
    }

    /**
     * Stops the underlying real source and clears all subscribers.
     * After this call no further samples or events will be delivered.
     */
    public void stop()
    {
        if(mStopped.compareAndSet(false, true))
        {
            mRealSource.setListener(null);
            mRealSource.setSourceEventListener(null);
            mRealSource.stop();
            mSubscribers.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Internal callbacks from the real source
    // -------------------------------------------------------------------------

    private void onComplexSamples(ComplexSamples samples)
    {
        if(mStopped.get())
        {
            return;
        }

        for(SubscriberSource sub : mSubscribers)
        {
            sub.deliver(samples);
        }
    }

    private void onSourceEvent(SourceEvent event)
    {
        if(mStopped.get())
        {
            return;
        }

        for(SubscriberSource sub : mSubscribers)
        {
            sub.deliverSourceEvent(event);
        }
    }

    // =========================================================================
    // SubscriberSource — a thin ComplexSource adapter backed by this fanout
    // =========================================================================

    /**
     * A thin {@link ComplexSource} that delivers samples and source events forwarded
     * by the parent {@link ComplexSampleFanout}.  Each probe chain gets one of these.
     */
    static class SubscriberSource extends ComplexSource implements ISourceEventListener
    {
        private final double mSampleRate;
        private final long mFrequency;
        private Listener<ComplexSamples> mSampleListener;
        private Listener<SourceEvent> mSourceEventListener;

        SubscriberSource(double sampleRate, long frequency)
        {
            mSampleRate = sampleRate;
            mFrequency = frequency;
        }

        @Override
        public SampleType getSampleType()
        {
            return SampleType.COMPLEX;
        }

        @Override
        public double getSampleRate()
        {
            return mSampleRate;
        }

        @Override
        public long getFrequency()
        {
            return mFrequency;
        }

        // ComplexSource / Provider<ComplexSamples>
        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
            mSampleListener = listener;
        }

        // ISourceEventListener
        @Override
        public Listener<SourceEvent> getSourceEventListener()
        {
            return mSourceEventListener;
        }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener)
        {
            mSourceEventListener = listener;
        }

        @Override
        public void removeSourceEventListener()
        {
            mSourceEventListener = null;
        }

        // Module lifecycle
        @Override
        public void reset() {}

        @Override
        public void start() {}

        @Override
        public void stop()
        {
            mSampleListener = null;
            mSourceEventListener = null;
        }

        @Override
        public void dispose()
        {
            stop();
        }

        // Called by the fanout to push samples into this subscriber
        void deliver(ComplexSamples samples)
        {
            if(mSampleListener != null)
            {
                mSampleListener.receive(samples);
            }
        }

        // Called by the fanout to forward source events to this subscriber
        void deliverSourceEvent(SourceEvent event)
        {
            if(mSourceEventListener != null)
            {
                mSourceEventListener.receive(event);
            }
        }
    }
}
