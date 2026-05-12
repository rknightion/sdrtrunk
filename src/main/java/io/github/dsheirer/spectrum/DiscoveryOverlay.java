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
package io.github.dsheirer.spectrum;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.channel.ShowDiscoveryRequest;
import io.github.dsheirer.module.discovery.Discovery;
import io.github.dsheirer.module.discovery.DiscoveryEvent;
import io.github.dsheirer.module.discovery.DiscoveryModel;
import io.github.dsheirer.module.discovery.DiscoveryState;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import io.github.dsheirer.preference.discovery.OverlayDisplay;
import io.github.dsheirer.sample.Listener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swing overlay painted on a layer of the {@link SpectralDisplayPanel}'s {@link javax.swing.JLayeredPane}
 * that draws faint dashed rectangles for each {@link Discovery} not yet added as a channel.
 *
 * <h3>Display behaviour</h3>
 * <ul>
 *   <li>Discoveries whose {@code createdChannel} is non-null are skipped — they already render
 *       as normal channel overlays on the {@link OverlayPanel}.</li>
 *   <li>Visibility is controlled by two independent toggles:
 *       {@link OverlayDisplay} from {@link DiscoveryPreference} (user preference) and a
 *       per-session {@link DiscoveryDisplay} that the operator sets via the context menu.</li>
 *   <li>When both indicate visibility, discoveries are coloured by state:
 *       ENERGY_DETECTED = dark grey, PROBING = mid-grey, IDENTIFIED = green-ish,
 *       UNIDENTIFIED = amber, ERROR = red.  All at low alpha so the spectrum is not obscured.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * {@link DiscoveryModel} fires {@link DiscoveryEvent}s on the FX thread when the FX toolkit is
 * running, or on whatever thread mutated the model in headless tests.  This overlay marshals
 * repaints to the Swing EDT via {@code SwingUtilities.invokeLater}.  The overlay reads the model
 * snapshot at paint time (on the EDT) — no locking needed because the EDT is single-threaded.
 *
 * <h3>Lifecycle</h3>
 * Call {@link #dispose()} when removing the overlay from the JLayeredPane to deregister the
 * {@link DiscoveryModel} listener and release resources.
 */
public class DiscoveryOverlay extends JComponent
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryOverlay.class);

    // ---- state colours (alpha 100 out of 255) --------------------------------
    private static final Color COLOR_ENERGY    = new Color(100, 100, 100, 90);  // grey
    private static final Color COLOR_PROBING   = new Color(150, 150, 200, 90);  // blue-grey
    private static final Color COLOR_IDENTIFIED= new Color( 60, 180,  80, 90);  // green
    private static final Color COLOR_UNIDENT   = new Color(200, 140,  30, 90);  // amber
    private static final Color COLOR_KNOWN     = new Color( 80,  80, 200, 70);  // blue
    private static final Color COLOR_ERROR     = new Color(200,  40,  40, 90);  // red

    // ---- stroke: dashed outline ---------------------------------------------
    private static final Stroke DASHED_STROKE = new BasicStroke(
        1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
        5.0f, new float[]{3.0f, 4.0f}, 0.0f);

    // ---- label font ---------------------------------------------------------
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 9);

    // ---- dependencies -------------------------------------------------------
    private final DiscoveryModel mDiscoveryModel;
    private final DiscoveryPreference mDiscoveryPreference;
    private final OverlayPanel mOverlayPanel;

    // ---- per-session display toggle (from context menu) ---------------------
    private DiscoveryDisplay mDiscoveryDisplay = DiscoveryDisplay.IDENTIFIED_ONLY;

    // ---- DiscoveryModel listener (held so we can unregister on dispose) -----
    private final Listener<DiscoveryEvent> mDiscoveryListener;

    /**
     * Per-session display-mode toggle exposed in the right-click context menu.
     * Independent from the preference-based {@link OverlayDisplay} — both must
     * permit display for the overlay to render.
     */
    public enum DiscoveryDisplay
    {
        ALL,
        IDENTIFIED_ONLY,
        NONE
    }

    /**
     * Constructs the overlay.
     *
     * @param discoveryModel    the model whose rows are painted
     * @param discoveryPreference preference holding the global overlay-display mode
     * @param overlayPanel      used to convert frequencies to x-pixel positions
     */
    public DiscoveryOverlay(DiscoveryModel discoveryModel,
                             DiscoveryPreference discoveryPreference,
                             OverlayPanel overlayPanel)
    {
        mDiscoveryModel = discoveryModel;
        mDiscoveryPreference = discoveryPreference;
        mOverlayPanel = overlayPanel;

        setOpaque(false);

        // Register a discovery listener that triggers a repaint on the EDT
        mDiscoveryListener = event -> SwingUtilities.invokeLater(this::repaint);
        mDiscoveryModel.addListener(mDiscoveryListener);

        // Click → post ShowDiscoveryRequest for the closest discovery
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if(!SwingUtilities.isLeftMouseButton(e)) return;

                long clickedFreq = mOverlayPanel.getFrequencyFromAxis(e.getX());
                Discovery closest = findClosestDiscovery(clickedFreq);

                if(closest != null)
                {
                    MyEventBus.getGlobalEventBus()
                        .post(new ShowDiscoveryRequest(closest.getCenterFrequencyHz()));
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sets the per-session display toggle.  Triggers a repaint.
     *
     * @param display new display mode; must not be null
     */
    public void setDiscoveryDisplay(DiscoveryDisplay display)
    {
        if(display == null) return;
        mDiscoveryDisplay = display;
        SwingUtilities.invokeLater(this::repaint);
    }

    /**
     * Returns the current per-session display mode.
     *
     * @return current display mode
     */
    public DiscoveryDisplay getDiscoveryDisplay()
    {
        return mDiscoveryDisplay;
    }

    /**
     * Deregisters the model listener.  Call when removing the component.
     */
    public void dispose()
    {
        mDiscoveryModel.removeListener(mDiscoveryListener);
    }

    // -------------------------------------------------------------------------
    // Painting
    // -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g)
    {
        // Check both the global preference and the session toggle
        if(!shouldShowAny())
        {
            return;
        }

        List<Discovery> snapshot = mDiscoveryModel.snapshot();

        if(snapshot.isEmpty())
        {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();

        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setFont(LABEL_FONT);
            FontMetrics fm = g2.getFontMetrics();
            int panelHeight = getHeight();

            for(Discovery discovery : snapshot)
            {
                // Skip discoveries that have already been added as channels —
                // they are rendered by OverlayPanel already
                if(discovery.getCreatedChannel() != null)
                {
                    continue;
                }

                if(!shouldShowDiscovery(discovery))
                {
                    continue;
                }

                long centerHz = discovery.getCenterFrequencyHz();
                int bwHz      = discovery.getBandwidthHz();

                // Guard: if bandwidth is 0 use a small default so it's still visible
                if(bwHz == 0)
                {
                    bwHz = 12_500;
                }

                long minHz = centerHz - bwHz / 2L;
                long maxHz = centerHz + bwHz / 2L;

                int xLeft  = (int) mOverlayPanel.getAxisFromFrequency(minHz);
                int xRight = (int) mOverlayPanel.getAxisFromFrequency(maxHz);

                // Skip if entirely outside the visible span
                if(xRight < 0 || xLeft > getWidth())
                {
                    continue;
                }

                xLeft  = Math.max(0, xLeft);
                xRight = Math.min(getWidth() - 1, xRight);
                int rectW = xRight - xLeft;

                if(rectW < 2)
                {
                    rectW = 2;
                    xLeft = Math.max(0, xLeft - 1);
                }

                Color stateColor = stateColor(discovery.getState());

                // Fill with low-alpha colour
                g2.setColor(stateColor);
                g2.fillRect(xLeft, 0, rectW, panelHeight);

                // Dashed border
                Stroke prevStroke = g2.getStroke();
                g2.setStroke(DASHED_STROKE);
                g2.setColor(stateColor.brighter());
                g2.drawRect(xLeft, 0, rectW - 1, panelHeight - 1);
                g2.setStroke(prevStroke);

                // Small label at top: decoder name or state abbreviation
                String label = makeLabel(discovery);

                if(label != null && !label.isEmpty())
                {
                    int textX = xLeft + Math.max(1, (rectW - fm.stringWidth(label)) / 2);
                    int textY = fm.getAscent() + 2;

                    // Shadow
                    g2.setColor(new Color(0, 0, 0, 160));
                    g2.drawString(label, textX + 1, textY + 1);

                    // Text
                    g2.setColor(Color.WHITE);
                    g2.drawString(label, textX, textY);
                }
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean shouldShowAny()
    {
        if(mDiscoveryDisplay == DiscoveryDisplay.NONE)
        {
            return false;
        }

        OverlayDisplay prefDisplay = mDiscoveryPreference.getOverlayDisplay();
        return prefDisplay != OverlayDisplay.NONE;
    }

    private boolean shouldShowDiscovery(Discovery discovery)
    {
        DiscoveryState state = discovery.getState();

        // IDENTIFIED_ONLY mode — show only IDENTIFIED rows
        if(mDiscoveryDisplay == DiscoveryDisplay.IDENTIFIED_ONLY
            || mDiscoveryPreference.getOverlayDisplay() == OverlayDisplay.IDENTIFIED_ONLY)
        {
            return state == DiscoveryState.IDENTIFIED;
        }

        // ALL mode — show everything (except KNOWN — those are already-configured channels)
        return state != DiscoveryState.KNOWN;
    }

    private static Color stateColor(DiscoveryState state)
    {
        if(state == null) return COLOR_ENERGY;

        return switch(state)
        {
            case ENERGY_DETECTED -> COLOR_ENERGY;
            case PROBING         -> COLOR_PROBING;
            case IDENTIFIED      -> COLOR_IDENTIFIED;
            case UNIDENTIFIED    -> COLOR_UNIDENT;
            case KNOWN           -> COLOR_KNOWN;
            case ERROR           -> COLOR_ERROR;
        };
    }

    private static String makeLabel(Discovery discovery)
    {
        return switch(discovery.getState())
        {
            case ENERGY_DETECTED -> "⚡";
            case PROBING         -> "…";
            case IDENTIFIED      -> discovery.getDetectedDecoder() != null
                ? discovery.getDetectedDecoder().getShortDisplayString() : "✓";
            case UNIDENTIFIED    -> "?";
            case KNOWN           -> "known";
            case ERROR           -> "✕";
        };
    }

    private Discovery findClosestDiscovery(long clickedFreqHz)
    {
        Discovery closest = null;
        long bestDelta = Long.MAX_VALUE;

        for(Discovery d : mDiscoveryModel.snapshot())
        {
            if(d.getCreatedChannel() != null || !shouldShowDiscovery(d))
            {
                continue;
            }

            long delta = Math.abs(d.getCenterFrequencyHz() - clickedFreqHz);

            if(delta < bestDelta)
            {
                bestDelta = delta;
                closest = d;
            }
        }

        return closest;
    }
}
