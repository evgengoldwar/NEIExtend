package neiextend.profiler;

import java.lang.reflect.Field;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;

import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLEvent;

/**
 * Times the events FML sends to each mod without mixing into {@link LoadController}: some packs load it before mixins
 * can change it, which stops the game. Each mod's event bus in the controller is swapped for one timing what it is
 * sent. It is put in place while the mods are constructed, so the mods constructed before this one are not timed for
 * their construction.
 */
public final class ModEventTimer {

    /** The kind of the last event sent, to tell when FML goes on to its next phase. */
    private static String lastPhase;

    private ModEventTimer() {}

    public static void install() {
        if (!LoadProfiler.isActive()) return;
        try {
            Field controllerField = Loader.class.getDeclaredField("modController");
            controllerField.setAccessible(true);
            LoadController controller = (LoadController) controllerField.get(Loader.instance());
            Field channelsField = LoadController.class.getDeclaredField("eventChannels");
            channelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, EventBus> channels = (Map<String, EventBus>) channelsField.get(controller);
            if (channels == null) {
                LoadProfiler.LOG.warn("FML has no mod event buses yet, mod events are not timed");
                return;
            }
            ImmutableMap.Builder<String, EventBus> timed = ImmutableMap.builder();
            for (Map.Entry<String, EventBus> entry : channels.entrySet()) {
                EventBus bus = entry.getValue();
                timed.put(entry.getKey(), bus instanceof TimedBus ? bus : new TimedBus(entry.getKey(), bus));
            }
            channelsField.set(controller, timed.build());
            LoadProfiler.milestone("Mod event timing in place (" + channels.size() + " mods)");
        } catch (Throwable t) {
            LoadProfiler.LOG.warn("Could not time the events sent to mods", t);
        }
    }

    /** A mod's event bus, timing every event posted to it. */
    private static final class TimedBus extends EventBus {

        private final String modId;
        private final EventBus delegate;

        TimedBus(String modId, EventBus delegate) {
            super(modId);
            this.modId = modId;
            this.delegate = delegate;
        }

        @Override
        public void register(Object object) {
            delegate.register(object);
        }

        @Override
        public void unregister(Object object) {
            delegate.unregister(object);
        }

        @Override
        public void post(Object event) {
            if (!(event instanceof FMLEvent) || !LoadProfiler.isActive()) {
                delegate.post(event);
                return;
            }
            String phase = event.getClass()
                .getSimpleName();
            if (!phase.equals(lastPhase)) {
                lastPhase = phase;
                LoadProfiler.onLoaderStateTransition(phase);
            }
            LoadProfiler.onModEventStart();
            try {
                delegate.post(event);
            } finally {
                ModContainer mod = Loader.instance()
                    .getIndexedModList()
                    .get(modId);
                LoadProfiler
                    .onModEventEnd(modId, mod != null ? mod.getName() : modId, ((FMLEvent) event).getEventType());
            }
        }
    }
}
