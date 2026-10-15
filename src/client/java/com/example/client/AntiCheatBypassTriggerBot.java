package com.example.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class AntiCheatBypassTriggerBot implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private boolean isEnabled = false;

    // Anti-Cheat State Machine Variables
    private int originalSlot = -1;
    private int ticksUntilSwapBack = -1;
    private int attackJitterCooldown = 0;
    private final Random random = new Random();

    @Override
    public void onInitializeClient() {
        // 1. Register the Keybind (Default: Key 'R')
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.examplemod.toggle_triggerbot",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.examplemod.hacks"
        ));

        // 2. Register the tick processing thread
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;

        // Handle Toggle Interaction
        while (toggleKey.wasPressed()) {
            isEnabled = !isEnabled;
            client.player.sendMessage(Text.literal("Triggerbot: " + 
                    (isEnabled ? Formatting.GREEN + "ENABLED" : Formatting.RED + "DISABLED")), true);
        }

        // Process scheduled hotbar rollbacks (Anti-Cheat Bypass part 1)
        if (ticksUntilSwapBack > 0) {
            ticksUntilSwapBack--;
            if (ticksUntilSwapBack == 0 && originalSlot != -1) {
                client.player.getInventory().selectedSlot = originalSlot;
                
                // 1.21 Network Safe Synchronization
                if (client.getNetworkHandler() != null) {
                    client.interactionManager.syncSelectedSlot();
                }
                originalSlot = -1; // Reset state
            }
        }

        if (!isEnabled) return;

        // Process attack variance timers (Anti-Cheat Bypass part 2)
        if (attackJitterCooldown > 0) {
            attackJitterCooldown--;
            return;
        }

        // 3. Raycast Target Validation
        HitResult hit = client.crosshairTarget;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hit;
            Entity target = entityHit.getEntity();

            if (target instanceof LivingEntity livingTarget && livingTarget.isAlive()) {
                evaluateAttackConditions(client, livingTarget);
            }
        }
    }

    private void evaluateAttackConditions(MinecraftClient client, LivingEntity target) {
        ClientPlayerEntity player = client.player;

        // BRANCH 1: Target is actively shield-blocking -> EXECUTE ANTI-CHEAT BYPASS STUN
        if (target.isBlocking()) {
            executeShieldStunSequence(client, target);
            return;
        }

        // BRANCH 2: Target is vulnerable -> WAIT FOR 100% ATTACK CHARGE METER
        if (player.getAttackCooldownProgress(0.5f) >= 1.0f) {
            client.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            
            // Apply human click variance (adds 0 to 3 extra ticks of random delay between attacks)
            this.attackJitterCooldown = random.nextInt(4); 
        }
    }

    private void executeShieldStunSequence(MinecraftClient client, LivingEntity target) {
        ClientPlayerEntity player = client.player;
        ClientPlayerInteractionManager manager = client.interactionManager;

        // Ensure we are not already waiting for an active item swap recovery window
        if (ticksUntilSwapBack > 0) return;

        int axeSlot = findAxeInHotbar(player);
        if (axeSlot == -1) {
            // No axe available, fallback to regular weapon if charge is completely filled
            if (player.getAttackCooldownProgress(0.5f) >= 1.0f) {
                manager.attackEntity(player, target);
                player.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        int activeSlot = player.getInventory().selectedSlot;

        if (activeSlot == axeSlot) {
            manager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            this.attackJitterCooldown = 2 + random.nextInt(3); // Apply standard recovery jitter
            return;
        }

        // Anti-Cheat Safe Sequence Injection:
        // Step 1: Lock the current item configuration index
        this.originalSlot = activeSlot;

        // Step 2: Swap client configuration instantly to Axe
        player.getInventory().selectedSlot = axeSlot;
        if (client.getNetworkHandler() != null) {
            manager.syncSelectedSlot();
        }

        // Step 3: Trigger the strike interaction packet
        manager.attackEntity(player, target);
        player.swingHand(Hand.MAIN_HAND);

        // Step 4: Add delay (1 to 2 server ticks) before permitting a hotbar item swap rollback
        this.ticksUntilSwapBack = 1 + random.nextInt(2);

        // Step 5: Prevent immediate retick spamming
        this.attackJitterCooldown = 5 + random.nextInt(4);
    }

    private int findAxeInHotbar(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }
}
