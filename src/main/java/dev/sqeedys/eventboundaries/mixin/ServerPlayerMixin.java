package dev.sqeedys.eventboundaries.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sqeedys.eventboundaries.EventBoundaries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    private static final String TELEPORT = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;";

    @Inject(method = TELEPORT, at = @At("HEAD"), cancellable = true)
    private void eventboundaries$transition(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        if (EventBoundaries.rejectTeleport((ServerPlayer) (Object) this, transition)) {
            cir.setReturnValue(null);
        }
    }

    @WrapOperation(method = TELEPORT, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V"),
            require = 2)
    private void eventboundaries$applyTransition(ServerGamePacketListenerImpl handler, PositionMoveRotation change,
                                                        Set<Relative> relatives, Operation<Void> operation) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        EventBoundaries.withApprovedTeleport(player, () -> operation.call(handler, change, relatives));
        EventBoundaries.recordTeleport(player);
    }

    @Inject(method = "setCamera", at = @At("HEAD"), cancellable = true)
    private void eventboundaries$camera(Entity camera, CallbackInfo ci) {
        if (EventBoundaries.rejectCamera((ServerPlayer) (Object) this, camera)) {
            ci.cancel();
        }
    }

    @Inject(method = "showEndCredits", at = @At("HEAD"), cancellable = true)
    private void eventboundaries$credits(CallbackInfo ci) {
        if (EventBoundaries.rejectCreditsExit((ServerPlayer) (Object) this)) {
            ci.cancel();
        }
    }
}
