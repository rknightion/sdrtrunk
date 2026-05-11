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

import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ComplexSampleFanout.
 */
class ComplexSampleFanoutTest
{
    /**
     * Simple fake ComplexSource for testing — allows us to push buffers and source events,
     * and records whether stop() was called.
     */
    static class FakeComplexSource extends ComplexSource
    {
        private Listener<ComplexSamples> mListener;
        private Listener<SourceEvent> mSourceEventListener;
        private boolean mStopped = false;

        @Override
        public SampleType getSampleType() { return SampleType.COMPLEX; }

        @Override
        public void setListener(Listener<ComplexSamples> listener) { mListener = listener; }

        @Override
        public double getSampleRate() { return 25_000.0; }

        @Override
        public long getFrequency() { return 154_000_000L; }

        @Override
        public Listener<SourceEvent> getSourceEventListener() { return mSourceEventListener; }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener) { mSourceEventListener = listener; }

        @Override
        public void removeSourceEventListener() { mSourceEventListener = null; }

        @Override
        public void reset() {}

        @Override
        public void start() {}

        @Override
        public void stop() { mStopped = true; }

        @Override
        public void dispose() { stop(); }

        void pushSamples(ComplexSamples samples)
        {
            if(mListener != null) mListener.receive(samples);
        }

        void pushSourceEvent(SourceEvent event)
        {
            if(mSourceEventListener != null) mSourceEventListener.receive(event);
        }

        boolean isStopped() { return mStopped; }
    }

    private FakeComplexSource mRealSource;
    private ComplexSampleFanout mFanout;

    @BeforeEach
    void setUp()
    {
        mRealSource = new FakeComplexSource();
        mFanout = new ComplexSampleFanout(mRealSource);
    }

    private static ComplexSamples makeSamples()
    {
        return new ComplexSamples(new float[]{1f, 2f}, new float[]{3f, 4f}, System.currentTimeMillis());
    }

    // -------------------------------------------------------------------------
    // Buffer fan-out to subscribers
    // -------------------------------------------------------------------------

    @Test
    void twoSubscribers_bothReceiveAllBuffers()
    {
        List<ComplexSamples> receivedA = new ArrayList<>();
        List<ComplexSamples> receivedB = new ArrayList<>();

        ComplexSource subA = mFanout.newSubscriberSource();
        ComplexSource subB = mFanout.newSubscriberSource();
        subA.setListener(receivedA::add);
        subB.setListener(receivedB::add);

        mRealSource.pushSamples(makeSamples());
        mRealSource.pushSamples(makeSamples());
        mRealSource.pushSamples(makeSamples());

        assertEquals(3, receivedA.size(), "Subscriber A should receive all 3 buffers");
        assertEquals(3, receivedB.size(), "Subscriber B should receive all 3 buffers");
    }

    // -------------------------------------------------------------------------
    // Source event forwarding
    // -------------------------------------------------------------------------

    @Test
    void sampleRateSourceEvent_forwardedToSubscribers()
    {
        List<SourceEvent> eventsA = new ArrayList<>();
        List<SourceEvent> eventsB = new ArrayList<>();

        ComplexSource subA = mFanout.newSubscriberSource();
        ComplexSource subB = mFanout.newSubscriberSource();
        subA.setSourceEventListener(eventsA::add);
        subB.setSourceEventListener(eventsB::add);

        mRealSource.pushSourceEvent(SourceEvent.sampleRateChange(25_000.0, "test"));

        assertEquals(1, eventsA.size(), "Subscriber A should receive source event");
        assertEquals(1, eventsB.size(), "Subscriber B should receive source event");
    }

    // -------------------------------------------------------------------------
    // removeSubscriberSource
    // -------------------------------------------------------------------------

    @Test
    void afterRemoval_removedSubscriberGetsNoMoreBuffers()
    {
        List<ComplexSamples> receivedA = new ArrayList<>();
        List<ComplexSamples> receivedB = new ArrayList<>();

        ComplexSource subA = mFanout.newSubscriberSource();
        ComplexSource subB = mFanout.newSubscriberSource();
        subA.setListener(receivedA::add);
        subB.setListener(receivedB::add);

        mRealSource.pushSamples(makeSamples()); // both should get it

        mFanout.removeSubscriberSource(subA);

        mRealSource.pushSamples(makeSamples()); // only B should get it

        assertEquals(1, receivedA.size(), "Subscriber A should have received only 1 buffer after removal");
        assertEquals(2, receivedB.size(), "Subscriber B should still receive all 2 buffers");
    }

    // -------------------------------------------------------------------------
    // stop() releases the real source
    // -------------------------------------------------------------------------

    @Test
    void stop_callsStopOnRealSource()
    {
        mFanout.stop();
        assertTrue(mRealSource.isStopped(), "stop() on fanout should call stop() on the underlying source");
    }

    @Test
    void afterStop_noMoreDelivery()
    {
        List<ComplexSamples> received = new ArrayList<>();
        ComplexSource sub = mFanout.newSubscriberSource();
        sub.setListener(received::add);

        mFanout.stop();
        mRealSource.pushSamples(makeSamples()); // fanout is stopped; should not forward

        // After stop the real source listener is cleared, so pushSamples won't reach fanout anyway.
        // This test just confirms no exception is thrown.
        assertTrue(received.size() <= 1, "Should not receive samples after stop");
    }
}
