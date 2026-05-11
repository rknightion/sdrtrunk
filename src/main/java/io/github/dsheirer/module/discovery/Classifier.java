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

import java.util.concurrent.CompletableFuture;

/**
 * Testability seam for the signal classification operation used by {@link BandScanController}.
 *
 * <p>The production binding is {@link SignalClassifier}, which implements this interface.
 * Unit tests inject a fake implementation that returns scripted {@link ClassificationResult}
 * values without requiring a real tuner or processing chain.</p>
 */
public interface Classifier
{
    /**
     * Classifies the signal described by the request.
     *
     * <p>The returned future never completes exceptionally; all errors are encoded as
     * {@link ClassificationOutcome#ERROR} results.  Calling {@code future.cancel(true)}
     * interrupts the worker and produces a {@link ClassificationOutcome#CANCELLED} result.</p>
     *
     * @param request classification parameters
     * @return a future resolving to the classification result; cancellable
     */
    CompletableFuture<ClassificationResult> classify(ClassificationRequest request);
}
