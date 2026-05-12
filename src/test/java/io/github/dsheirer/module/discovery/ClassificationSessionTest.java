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

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClassificationSession (resource-scope lifecycle).
 */
class ClassificationSessionTest
{
    // -------------------------------------------------------------------------
    // Minimal stubs
    // -------------------------------------------------------------------------

    /**
     * A ProcessingChain subclass that counts stop() and dispose() calls without
     * actually running any real processing modules.
     */
    static class CountingChain extends ProcessingChain
    {
        final AtomicInteger stopCount = new AtomicInteger();
        final AtomicInteger disposeCount = new AtomicInteger();

        CountingChain()
        {
            super(new Channel(), new AliasModel());
        }

        @Override
        public void stop()
        {
            stopCount.incrementAndGet();
        }

        @Override
        public void dispose()
        {
            disposeCount.incrementAndGet();
        }
    }

    /**
     * A ComplexSource stub that tracks whether stop() was called.
     */
    static class StubComplexSource extends ComplexSource
    {
        boolean stopped = false;

        @Override public SampleType getSampleType() { return SampleType.COMPLEX; }
        @Override public void setListener(Listener<ComplexSamples> listener) {}
        @Override public double getSampleRate() { return 25_000.0; }
        @Override public long getFrequency() { return 154_000_000L; }
        @Override public Listener<SourceEvent> getSourceEventListener() { return null; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) {}
        @Override public void removeSourceEventListener() {}
        @Override public void reset() {}
        @Override public void start() {}
        @Override public void stop() { stopped = true; }
        @Override public void dispose() { stop(); }
    }

    private ClassificationSession buildSession()
    {
        StubComplexSource src = new StubComplexSource();
        ComplexSampleFanout fanout = new ComplexSampleFanout(src);
        return new ClassificationSession(src, fanout);
    }

    // -------------------------------------------------------------------------
    // Basic accessors
    // -------------------------------------------------------------------------

    @Test
    void getters_returnConstructorArgs()
    {
        StubComplexSource src = new StubComplexSource();
        ComplexSampleFanout fanout = new ComplexSampleFanout(src);
        ClassificationSession session = new ClassificationSession(src, fanout);

        assertSame(src, session.getRealSource());
        assertSame(fanout, session.getFanout());
    }

    // -------------------------------------------------------------------------
    // close() stops all registered probe chains
    // -------------------------------------------------------------------------

    @Test
    void close_stopsAndDisposesAllProbeChains()
    {
        ClassificationSession session = buildSession();
        CountingChain chainA = new CountingChain();
        CountingChain chainB = new CountingChain();

        session.addProbeChain(chainA);
        session.addProbeChain(chainB);

        session.close();

        assertEquals(1, chainA.stopCount.get(), "chainA should be stopped exactly once");
        assertEquals(1, chainA.disposeCount.get(), "chainA should be disposed exactly once");
        assertEquals(1, chainB.stopCount.get(), "chainB should be stopped exactly once");
        assertEquals(1, chainB.disposeCount.get(), "chainB should be disposed exactly once");
    }

    // -------------------------------------------------------------------------
    // close() is idempotent
    // -------------------------------------------------------------------------

    @Test
    void close_isIdempotent()
    {
        ClassificationSession session = buildSession();
        CountingChain chain = new CountingChain();
        session.addProbeChain(chain);

        session.close();
        session.close(); // second call must be a no-op

        assertEquals(1, chain.stopCount.get(), "stop must be called only once despite two close() calls");
        assertEquals(1, chain.disposeCount.get(), "dispose must be called only once despite two close() calls");
    }

    @Test
    void close_setsClosed()
    {
        ClassificationSession session = buildSession();
        assertFalse(session.isClosed());
        session.close();
        assertTrue(session.isClosed());
    }

    // -------------------------------------------------------------------------
    // removeProbeChain() prevents cleanup for that chain
    // -------------------------------------------------------------------------

    @Test
    void removeProbeChain_removedChainIsNotStopped()
    {
        ClassificationSession session = buildSession();
        CountingChain chainA = new CountingChain();
        CountingChain chainB = new CountingChain();

        session.addProbeChain(chainA);
        session.addProbeChain(chainB);

        session.removeProbeChain(chainA);
        session.close();

        assertEquals(0, chainA.stopCount.get(), "removed chain should not be stopped");
        assertEquals(1, chainB.stopCount.get(), "retained chain should still be stopped");
    }

    // -------------------------------------------------------------------------
    // close() tolerates exceptions from individual chains
    // -------------------------------------------------------------------------

    @Test
    void close_continuesAfterExceptionInOneChain()
    {
        ClassificationSession session = buildSession();

        // A chain whose stop() throws
        ProcessingChain explosiveChain = new ProcessingChain(new Channel(), new AliasModel())
        {
            @Override
            public void stop() { throw new RuntimeException("simulated stop failure"); }
            @Override
            public void dispose() { /* fine */ }
        };

        CountingChain goodChain = new CountingChain();
        session.addProbeChain(explosiveChain);
        session.addProbeChain(goodChain);

        // Should not propagate the exception
        assertDoesNotThrow(session::close);
        assertEquals(1, goodChain.stopCount.get(), "good chain must still be stopped after bad chain's exception");
    }

    // -------------------------------------------------------------------------
    // try-with-resources usage
    // -------------------------------------------------------------------------

    @Test
    void tryWithResources_closesAutomatically()
    {
        StubComplexSource src = new StubComplexSource();
        ComplexSampleFanout fanout = new ComplexSampleFanout(src);
        CountingChain chain = new CountingChain();

        try(ClassificationSession session = new ClassificationSession(src, fanout))
        {
            session.addProbeChain(chain);
        }

        assertTrue(chain.stopCount.get() > 0, "try-with-resources must have triggered close()");
        assertTrue(src.stopped, "fanout.stop() must have stopped the underlying source");
    }
}
