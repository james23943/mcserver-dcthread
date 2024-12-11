// Minecraft & Fabric imports
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;

// Discord imports
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.presence.ClientPresence;
import discord4j.core.object.presence.ClientActivity;

// Third-party imports
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;
import reactor.core.publisher.Mono;

// Java imports
import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.*;

public class DiscordBridgeMod implements ModInitializer {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build();
    private final Gson gson = new Gson();
    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Config config;
    private MinecraftServer server;
    private GatewayDiscordClient discordClient;
    private int playerCount = 0;
    private Instant startTime;
    private LinkedAccounts linkedAccounts = new LinkedAccounts();
    private Random random = new Random();

    private static class Config {
        String webhook_url;
        String thread_id;
        String bot_token;
    }

    private static class LinkedAccounts {
        Map<String, String> discordToMinecraft = new HashMap<>();
        Map<String, String> verificationCodes = new HashMap<>();
    }

    @Override
    public void onInitialize() {
        loadConfig();
        loadLinkedAccounts();
        setupDiscordBot();
        startTime = Instant.now();
        setupMessageQueue();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            this.server = server;
            queueMessage("The server is live! Join us here: **51.161.207.149:25574**\n\nDetails: https://docs.google.com/document/d/1S2M-LyrxpnSr_6epuYnKIH2guiOCYABxFeMYDPp_Duo/edit?tab=t.0");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> 
            queueMessage("Server is shutting down...")
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            playerCount++;
            updateBotStatus();
            ServerPlayerEntity player = handler.getPlayer();
            queueMessage(player.getName().getString() + " joined the game");
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            playerCount--;
            updateBotStatus();
            ServerPlayerEntity player = handler.getPlayer();
            queueMessage(player.getName().getString() + " left the game");
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String msg = message.getContent().getString();

            if (msg.startsWith("/dclink")) {
                handleDclinkCommand(msg, sender);
                return;
            }

            switch (msg) {
                case "/players":
                    showPlayerList(sender);
                    break;
                case "/uptime":
                    showUptime(sender);
                    break;
                default:
                    sendWebhookMessage(
                        message.getContent().getString(),
                        sender.getName().getString(),
                        "https://mc-heads.net/avatar/" + sender.getUuid().toString()
                    );
            }
        });

        ServerEntityCombatEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity) {
                queueMessage(source.getDeathMessage().getString());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.getAdvancementTracker().setListener((advancement, criterionName) -> {
                if (advancement.getDisplay() != null && advancement.getDisplay().shouldAnnounce()) {
                    queueMessage(player.getName().getString() + " has made the advancement [" +
                        advancement.getDisplay().getTitle().getString() + "]");
                }
            });
        });
    }

    private void setupMessageQueue() {
        scheduler.scheduleAtFixedRate(() -> {
            String message;
            while ((message = messageQueue.poll()) != null) {
                try {
                    sendBotMessage(message);
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void queueMessage(String message) {
        messageQueue.offer(message);
    }

    private void showPlayerList(ServerPlayerEntity sender) {
        int online = server.getCurrentPlayerCount();
        String playerList = String.join(", ",
            server.getPlayerManager().getPlayerList().stream()
                .map(p -> p.getName().getString())
                .toList()
        );
        sender.sendMessage(Text.literal("Online players (" + online + "): " + playerList));
    }

    private void showUptime(ServerPlayerEntity sender) {
        Duration uptime = Duration.between(startTime, Instant.now());
        sender.sendMessage(Text.literal(
            String.format("Server uptime: %d days, %d hours, %d minutes",
                uptime.toDays(), uptime.toHoursPart(), uptime.toMinutesPart())
        ));
    }

    private void handleDclinkCommand(String msg, ServerPlayerEntity sender) {
        String[] args = msg.split(" ");
        if (args.length == 2) {
            String code = args[1];
            String discordId = linkedAccounts.verificationCodes.get(code);
            if (discordId != null) {
                linkedAccounts.discordToMinecraft.put(discordId, sender.getUuid().toString());
                linkedAccounts.verificationCodes.remove(code);
                saveLinkedAccounts();
                sender.sendMessage(Text.literal("Successfully linked your Discord account!"));
            } else {
                sender.sendMessage(Text.literal("Invalid verification code!"));
            }
        }
    }

    private void setupDiscordBot() {
        DiscordClient.create(config.bot_token)
            .withGateway(client -> {
                this.discordClient = client;
                updateBotStatus();

                client.getRestClient().getApplicationService()
                    .createGlobalApplicationCommand(client.getApplicationId().block(), ApplicationCommandRequest.builder()
                        .name("link")
                        .description("Link your Discord account to Minecraft")
                        .build())
                    .subscribe();

                client.on(ChatInputInteractionEvent.class)
                    .filter(event -> event.getCommandName().equals("link"))
                    .subscribe(event -> {
                        String discordId = event.getInteraction().getUser().getId().asString();
                        String code = generateVerificationCode();
                        linkedAccounts.verificationCodes.put(code, discordId);
                        event.reply()
                            .withContent("Your verification code is: " + code + "\nUse /dclink <code> in Minecraft to link your account")
                            .withEphemeral(true)
                            .subscribe();
                    });

                client.on(MessageCreateEvent.class)
                    .filter(event -> event.getMessage().getChannelId().asString().equals(config.thread_id))
                    .filter(event -> !event.getMessage().getAuthor().map(user -> user.isBot()).orElse(true))
                    .subscribe(event -> {
                        String discordId = event.getMessage().getAuthor().get().getId().asString();
                        String displayName = event.getMessage().getAuthor().map(user -> user.getUsername()).orElse("Unknown");

                        if (linkedAccounts.discordToMinecraft.containsKey(discordId)) {
                            String minecraftUuid = linkedAccounts.discordToMinecraft.get(discordId);
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(UUID.fromString(minecraftUuid));
                            if (player != null) {
                                displayName = player.getName().getString();
                            }
                        }

                        String content = event.getMessage().getContent();
                        if (server != null) {
                            server.getPlayerManager().broadcast(
                                Text.literal("§9[Discord] §r" + displayName + ": " + content),
                                false
                            );
                        }
                    });

                return Mono.never();
            })
            .subscribe();
    }

    private void updateBotStatus() {
        if (discordClient != null) {
            String status = (playerCount > 0)
                ? "#" + playerCount + " playing LonkMC"
                : "No-one playing LonkMC";
            discordClient.updatePresence(ClientPresence.online(ClientActivity.playing(status))).subscribe();
        }
    }

    private void sendBotMessage(String content) {
        if (discordClient != null) {
            discordClient.getChannelById(discord4j.common.util.Snowflake.of(config.thread_id))
                .createMessage(content)
                .subscribe();
        }
    }

    private String generateVerificationCode() {
        return String.format("%06d", random.nextInt(1000000));
    }

    private void loadConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("mcserver-dcthread-config.json")) {
            if (is == null) throw new RuntimeException("Config not found");
            config = gson.fromJson(new InputStreamReader(is), Config.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    private void saveLinkedAccounts() {
        try (FileWriter writer = new FileWriter("mcserver-dcthread-linked-accounts.json")) {
            gson.toJson(linkedAccounts, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadLinkedAccounts() {
        try (FileReader reader = new FileReader("mcserver-dcthread-linked-accounts.json")) {
            linkedAccounts = gson.fromJson(reader, LinkedAccounts.class);
        } catch (FileNotFoundException e) {
            linkedAccounts = new LinkedAccounts();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendWebhookMessage(String content, String username, String avatarUrl) {
        JsonObject json = new JsonObject();
        json.addProperty("content", content);
        json.addProperty("thread_id", config.thread_id);
        if (username != null) json.addProperty("username", username);
        if (avatarUrl != null) json.addProperty("avatar_url", avatarUrl);

        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
            .url(config.webhook_url)
            .post(body)
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }
}
