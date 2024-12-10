public class DiscordBridgeMod implements ModInitializer {
    private static final String WEBHOOK_URL = "YOUR_WEBHOOK_URL";
    private static final String API_URL = "YOUR_BOT_API_URL";
    
    @Override
    public void onInitialize() {
        // Server start event
        ServerLifecycleEvents.SERVER_STARTING.register(server -> 
            sendWebhookMessage("Server is starting..."));
            
        ServerLifecycleEvents.SERVER_STARTED.register(server -> 
            sendWebhookMessage("Server is now online!"));
            
        // Chat events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> 
            sendWebhookMessage(handler.getPlayer().getName().getString() + " joined the game"));
            
        // Death events
        ServerEntityCombatEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity) {
                sendWebhookMessage(entity.getName().getString() + " " + source.getDeathMessage().getString());
            }
        });
    }
    
    private void sendWebhookMessage(String content) {
        // Implementation for sending webhook messages
    }
}