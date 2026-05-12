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

import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.source.ComplexSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource scope for a single classification attempt.
 *
 * <p>A {@code ClassificationSession} owns:</p>
 * <ul>
 *   <li>The real {@link ComplexSource} (tuner channel) acquired for this request</li>
 *   <li>The {@link ComplexSampleFanout} that fans the real source out to probe chains</li>
 *   <li>All live probe {@link ProcessingChain}s started during the session</li>
 * </ul>
 *
 * <p>Calling {@link #close()} disposes all resources in a single try-with-resources block.
 * Subsequent calls to {@code close()} are safe (idempotent).  Individual per-resource
 * failures are logged but do not prevent cleanup of the remaining resources.</p>
 *
 * <p>Typical usage:</p>
 * <pre>
 *   try(ClassificationSession session = new ClassificationSession(source, fanout)) {
 *       ProbeChain pc = factory.build(type);
 *       session.addProbeChain(pc);
 *       pc.chain().setSource(fanout.newSubscriberSource());
 *       pc.chain().start();
 *       // ... wait for lock or timeout ...
 *   } // session.close() tears down everything
 * </pre>
 */
public class ClassificationSession implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ClassificationSession.class);

    private final ComplexSource mRealSource;
    private final ComplexSampleFanout mFanout;
    private final List<ProcessingChain> mProbeChains = new ArrayList<>();
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /**
     * Constructs a session owning the given source and fanout.
     *
     * @param realSource the underlying tuner channel source
     * @param fanout     the fanout already registered on the real source
     */
    public ClassificationSession(ComplexSource realSource, ComplexSampleFanout fanout)
    {
        mRealSource = realSource;
        mFanout = fanout;
    }

    /**
     * Registers a probe chain with this session so it will be stopped and disposed on close.
     *
     * @param probeChain the chain to manage
     */
    public void addProbeChain(ProcessingChain probeChain)
    {
        mProbeChains.add(probeChain);
    }

    /**
     * Removes a probe chain from management (e.g. if the caller wants to keep the winning chain
     * running after the session closes — unused in v1 where liveChain is always null).
     *
     * @param probeChain the chain to remove
     */
    public void removeProbeChain(ProcessingChain probeChain)
    {
        mProbeChains.remove(probeChain);
    }

    /** Returns the fanout for this session. */
    public ComplexSampleFanout getFanout()
    {
        return mFanout;
    }

    /** Returns the real source for this session. */
    public ComplexSource getRealSource()
    {
        return mRealSource;
    }

    /**
     * Stops and disposes all probe chains, then stops the fanout (which also stops the real source).
     * This method is idempotent — calling it more than once has no effect.
     */
    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            // Stop + dispose each probe chain (copy to avoid ConcurrentModificationException)
            for(ProcessingChain chain : new ArrayList<>(mProbeChains))
            {
                try
                {
                    chain.stop();
                }
                catch(Exception e)
                {
                    mLog.warn("Error stopping probe chain during session close", e);
                }

                try
                {
                    chain.dispose();
                }
                catch(Exception e)
                {
                    mLog.warn("Error disposing probe chain during session close", e);
                }
            }

            mProbeChains.clear();

            // Stop the fanout — this also stops the real source
            try
            {
                mFanout.stop();
            }
            catch(Exception e)
            {
                mLog.warn("Error stopping ComplexSampleFanout during session close", e);
            }
        }
    }

    /** Returns {@code true} if this session has already been closed. */
    public boolean isClosed()
    {
        return mClosed.get();
    }
}
