package dev.sqeedys.eventboundaries.mixin;

import dev.sqeedys.eventboundaries.EventBoundaries;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class PacketListenerMixin {
    @Shadow public ServerPlayer player;
    @Shadow private static double clampHorizontal(double coordinate) { throw new AssertionError(); }
    @Shadow private static double clampVertical(double coordinate) { throw new AssertionError(); }

    @Inject(method = "handlePlayerPositionChange", at = @At("HEAD"), cancellable = true)
    private void eventboundaries$walking(double x, double y, double z, float yaw, float pitch,
                                                boolean onGround, boolean horizontalCollision, CallbackInfo ci) {
        if (EventBoundaries.hasBoundaries() && !player.isPassenger()
                && EventBoundaries.rejectWalking(player, new Vec3(clampHorizontal(x), clampVertical(y), clampHorizontal(z)))) {
            EventBoundaries.resyncWalking(player);
            ci.cancel();
        }
    }

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            cancellable = true)
    private void eventboundaries$vehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (!EventBoundaries.hasBoundaries()) {
            return;
        }
        Entity vehicle = player.getRootVehicle();
        Vec3 position = packet.movingTo().position();
        Vec3 target = new Vec3(clampHorizontal(position.x), clampVertical(position.y), clampHorizontal(position.z));
        if (EventBoundaries.rejectVehicleMovement(vehicle, target)) {
            player.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
            ci.cancel();
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V",
            at = @At("HEAD"), cancellable = true)
    private void eventboundaries$directTeleport(PositionMoveRotation change, Set<Relative> relatives, CallbackInfo ci) {
        if (EventBoundaries.checkConnectionTeleport(player)
                && EventBoundaries.rejectTeleport(player, player.level(), change, relatives)) {
            ci.cancel();
        }
    }

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("RETURN"))
    private void eventboundaries$recordDirectTeleport(PositionMoveRotation change, Set<Relative> relatives, CallbackInfo ci) {
        if (EventBoundaries.checkConnectionTeleport(player)) {
            EventBoundaries.recordTeleport(player);
        }
    }

}
