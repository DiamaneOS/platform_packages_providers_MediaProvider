/* Copyright (C) 2026 DiamaneOS */
package com.android.providers.media.vmdecode;
import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.graphics.drawable.ColorDrawable;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class VmDialogReloadTest {
    static class QueuedView extends View {
        final List<Runnable> pending = new ArrayList<>();
        QueuedView(Context context) { super(context); }
        @Override public boolean postDelayed(Runnable work, long delay) { pending.add(work); return true; }
        @Override public boolean removeCallbacks(Runnable work) { pending.removeIf(r -> r == work); return true; }
        @Override public boolean post(Runnable work) { work.run(); return true; }
        @Override public boolean isAttachedToWindow() { return true; }
    }
    @Test public void readinessAndTimerCoalesceAndCloseStopsRetries() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            QueuedView view = new QueuedView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            AtomicInteger loads = new AtomicInteger();
            try (VmDialogReload reload = new VmDialogReload(view, loads::incrementAndGet)) {
                reload.start();
                reload.complete(true);
                assertEquals(1, view.pending.size());
                VmDecode.serviceAnswers();
                assertEquals(2, loads.get());
                assertTrue(view.pending.isEmpty());
                assertTrue(reload.firstResult());
                assertFalse(reload.firstResult());
                reload.complete(true);
                Runnable oldTimer = view.pending.get(0);
                reload.close();
                oldTimer.run();
                assertEquals(2, loads.get());
            }
        });
    }
    @Test public void recoveredFullPreviewRestoresImageLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ImageView view = new ImageView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setBackground(new ColorDrawable(0xff808080));
            view.setClipToOutline(true);
            VmDialogReload.recovered(view);
            assertEquals(ImageView.ScaleType.CENTER_INSIDE, view.getScaleType());
            assertNull(view.getBackground());
            assertFalse(view.getClipToOutline());
        });
    }
}
