import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import com.google.gson.Gson;
import okhttp3.*;
import java.io.*;

public class DiscordBridgeMod implements ModInitializer {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();
    private Config config;

    private static class Config {
        String webhook_url;
        String thread_id;
    }

    @Override
    public void onInitialize() {
        loadConfig();
        
        ServerLifecycleEvents.SERVER_STARTING.register(server -> 
            sendWebhookMessage("Server is starting...", "Server Status", null));
            
        ServerLifecycleEvents.SERVER_STARTED.register(server -> 
            sendWebhookMessage("Server is now online!", "Server Status", null));
            
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            sendWebhookMessage(
                player.getName().getString() + " joined the game",
                player.getName().getString(),
                "https://mc-heads.net/avatar/" + player.getUuid().toString()
            );
        });
    }

    private void loadConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.json")) {
            if (is == null) {
                throw new RuntimeException("Config not found");
            }
            config = gson.fromJson(new InputStreamReader(is), Config.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }
}