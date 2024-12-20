package com.mcserverdcthread;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.presence.ClientPresence;
import discord4j.core.object.presence.ClientActivity;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;
import reactor.core.publisher.Mono;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
        String startup_message;
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
            queueMessage(config.startup_message);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> 
            queueMessage("Server is shutting down...")
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            playerCount++;
            updateBotStatus();
            ServerPlayerEntity player = handler.getPlayer();
            queueMessage(player.getName().getString() + " joined the game");
            
            // Register advancement listener for this player
            player.getAdvancementTracker().addListener(new AdvancementCallback() {
                @Override
                public void onAdvancement(Advancement advancement) {
                    if (advancement.getDisplay() != null && advancement.getDisplay().shouldAnnounceToChat()) {
                        queueMessage(player.getName().getString() + " has made the advancement [" +
                            advancement.getDisplay().getTitle().getString() + "]");
                    }
                }
            });

        });        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
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
                case "/players" -> showPlayerList(sender);
                case "/uptime" -> showUptime(sender);
                default -> sendWebhookMessage(
                    message.getContent().getString(),
                    sender.getName().getString(),
                    "https://mc-heads.net/avatar/" + sender.getUuid().toString()
                );
            }
        });
    }

    private void setupDiscordBot() {
        DiscordClient.create(config.bot_token)
            .withGateway(gateway -> {
                this.discordClient = gateway;
                updateBotStatus();

                gateway.on(MessageCreateEvent.class)
                    .filter(event -> event.getMessage().getChannelId().asString().equals(config.thread_id))
                    .filter(event -> !event.getMessage().getAuthor().map(user -> user.isBot()).orElse(true))
                    .subscribe(event -> {
                        String discordId = event.getMessage().getAuthor().get().getId().asString();
                        String displayName = event.getMessage().getAuthor().map(user -> user.getUsername()).orElse("Unknown");
                        String content = event.getMessage().getContent();

                        if (content.startsWith("!link")) {
                            String code = generateVerificationCode();
                            linkedAccounts.verificationCodes.put(code, discordId);
                            event.getMessage().getChannel()
                                .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                                    .content("Your verification code is: " + code + 
                                    "\nUse /dclink <code> in Minecraft to link your account")
                                    .build()))
                                .subscribe();
                            return;
                        }

                        if (linkedAccounts.discordToMinecraft.containsKey(discordId)) {
                            String minecraftUuid = linkedAccounts.discordToMinecraft.get(discordId);
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(UUID.fromString(minecraftUuid));
                            if (player != null) {
                                displayName = player.getName().getString();
                            }
                        }

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

    private void sendBotMessage(String content) {
        if (discordClient != null) {
            discordClient.getChannelById(discord4j.common.util.Snowflake.of(config.thread_id))
                .createMessage(content)
                .subscribe();
        }
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

    private void updateBotStatus() {
        if (discordClient != null) {
            String status = (playerCount > 0)
                ? "#" + playerCount + " playing LonkMC"
                : "No-one playing LonkMC";
            discordClient.updatePresence(ClientPresence.online(ClientActivity.playing(status))).subscribe();
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
