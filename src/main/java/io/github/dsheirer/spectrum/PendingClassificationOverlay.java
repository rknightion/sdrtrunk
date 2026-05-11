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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translucent Swing overlay painted on a layer of the {@link javax.swing.JLayeredPane} while
 * a click-to-tune classification is in progress.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>Shows a translucent rectangle at the target frequency span.</li>
 *   <li>Animates an "Identifying…" label cycling through decoder abbreviations.</li>
 *   <li>Clicking anywhere on the overlay or pressing <kbd>Esc</kbd> fires the
 *       cancel callback and clears the overlay.</li>
 *   <li>{@link #setPending(long, int, OverlayPanel)} starts / replaces the animation;
 *       {@link #clear()} hides it.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All methods must be called on the Swing EDT.
 */
public class PendingClassificationOverlay extends JComponent
{
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(PendingClassificationOverlay.class);

    private static final Color COLOR_FILL   = new Color(0, 120, 220, 70);
    private static final Color COLOR_BORDER = new Color(0, 160, 255, 200);
    private static final Color COLOR_TEXT   = new Color(230, 230, 255, 255);

    private static final float ALPHA  = 0.75f;
    private static final int   CORNER = 4;

    private static final String[] SPINNER_FRAMES = {"  ●○○○ ", " ○●○○ ", " ○○●○ ", " ○○○● "};
    private static final String[] DECODER_HINTS  =
        {"DMR?", "P25-1?", "P25-2?", "NBFM?", "AM?", "LTR?", "MPT?", "Passport?"};

    /** Repaint interval in ms — fast enough for a smooth spinner. */
    private static final int TIMER_INTERVAL_MS = 250;

    // ---- state ---------------------------------------------------------------

    private long mCenterFreqHz;
    private int mWidthHz;

    /** The OverlayPanel used to convert frequency ↔ pixel-x. */
    private OverlayPanel mOverlayPanel;

    private boolean mActive;
    private int mAnimFrame;

    private Runnable mCancelCallback;
    private Timer mAnimTimer;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the overlay component.
     *
     * <p>The component starts fully transparent (nothing shown).  Install it on
     * the {@link javax.swing.JLayeredPane#PALETTE_LAYER} above the
     * {@link OverlayPanel}.</p>
     *
     * @param cancelCallback invoked (on the EDT) when the user cancels the pending classification
     */
    public PendingClassificationOverlay(Runnable cancelCallback)
    {
        mCancelCallback = cancelCallback;
        setOpaque(false);
        setVisible(false);

        // Click anywhere on this overlay → cancel
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                cancel();
            }
        });

        // Esc key → cancel  (component must have focus for key events)
        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if(e.getKeyCode() == KeyEvent.VK_ESCAPE)
                {
                    cancel();
                }
            }
        });

        setFocusable(true);

        // Animation timer
        mAnimTimer = new Timer(TIMER_INTERVAL_MS, new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                mAnimFrame++;
                repaint();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts (or replaces) the pending-classification display.
     *
     * @param centerFreqHz centre frequency of the signal being probed, in Hz
     * @param widthHz      width of the probed span, in Hz (≥ 0)
     * @param overlayPanel the overlay panel used to map frequency to pixel position
     */
    public void setPending(long centerFreqHz, int widthHz, OverlayPanel overlayPanel)
    {
        mCenterFreqHz = centerFreqHz;
        mWidthHz      = Math.max(0, widthHz);
        mOverlayPanel = overlayPanel;
        mActive       = true;
        mAnimFrame    = 0;

        setVisible(true);
        requestFocusInWindow();
        mAnimTimer.start();
        repaint();
    }

    /**
     * Hides the overlay and stops the animation timer.
     */
    public void clear()
    {
        mActive = false;
        mAnimTimer.stop();
        setVisible(false);
        repaint();
    }

    // -------------------------------------------------------------------------
    // Painting
    // -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g)
    {
        if(!mActive || mOverlayPanel == null)
        {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();

        try
        {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // --- compute pixel bounds -------------------------------------------
            int panelWidth  = mOverlayPanel.getWidth();
            int panelHeight = mOverlayPanel.getHeight();

            long halfWidth = mWidthHz / 2L;
            long minFreq   = mCenterFreqHz - halfWidth;
            long maxFreq   = mCenterFreqHz + halfWidth;

            int xLeft  = mOverlayPanel.getAxisFromFrequencyPublic(minFreq);
            int xRight = mOverlayPanel.getAxisFromFrequencyPublic(maxFreq);

            // Ensure at least a few pixels wide so it's always visible
            if(xRight - xLeft < 4)
            {
                int midX = (xLeft + xRight) / 2;
                xLeft  = midX - 2;
                xRight = midX + 2;
            }

            int rectX = Math.max(0, xLeft);
            int rectW = Math.min(xRight, panelWidth) - rectX;

            if(rectW <= 0)
            {
                return;
            }

            // --- fill -----------------------------------------------------------
            Composite original = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ALPHA));
            g2.setColor(COLOR_FILL);
            g2.fillRoundRect(rectX, 0, rectW, panelHeight, CORNER, CORNER);
            g2.setComposite(original);

            // --- border ---------------------------------------------------------
            Stroke origStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{4f, 3f}, 0f));
            g2.setColor(COLOR_BORDER);
            g2.drawRoundRect(rectX, 0, rectW - 1, panelHeight - 1, CORNER, CORNER);
            g2.setStroke(origStroke);

            // --- animated label -------------------------------------------------
            String spinnerFrame = SPINNER_FRAMES[mAnimFrame % SPINNER_FRAMES.length];
            String decoderHint  = DECODER_HINTS[(mAnimFrame / 2) % DECODER_HINTS.length];
            String label        = "Identifying… " + decoderHint + " " + spinnerFrame;

            Font font = g2.getFont().deriveFont(Font.BOLD, 11f);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();

            int textX = rectX + Math.max(2, (rectW - fm.stringWidth(label)) / 2);
            int textY = panelHeight / 2 + fm.getAscent() / 2;

            // Shadow
            g2.setColor(new Color(0, 0, 0, 180));
            g2.drawString(label, textX + 1, textY + 1);

            // Text
            g2.setColor(COLOR_TEXT);
            g2.drawString(label, textX, textY);
        }
        finally
        {
            g2.dispose();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void cancel()
    {
        clear();

        if(mCancelCallback != null)
        {
            mCancelCallback.run();
        }
    }
}
