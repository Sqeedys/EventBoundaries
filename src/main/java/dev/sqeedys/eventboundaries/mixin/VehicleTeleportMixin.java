package dev.sqeedys.eventboundaries.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.sqeedys.eventboundaries.EventBoundaries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class VehicleTeleportMixin {
    @ModifyExpressionValue(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 eventboundaries$resolvedMovement(Vec3 movement) {
        Entity self = (Entity) (Object) this;
        if (!EventBoundaries.hasBoundaries() || movement.lengthSqr() == 0) {
            return movement;
        }
        Vec3 target = self.position().add(movement);
        boolean denied = self instanceof ServerPlayer player
                ? EventBoundaries.rejectWalking(player, target)
                : self.isVehicle() && EventBoundaries.rejectVehicleMovement(self, target);
        return denied ? Vec3.ZERO : movement;
    }

    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), cancellable = true)
    private void eventboundaries$vehicleTransition(TeleportTransition transition, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (EventBoundaries.hasBoundaries() && self.isVehicle() && deniesPassenger(self, transition)) {
            cir.setReturnValue(null);
        }
    }

    private static boolean deniesPassenger(Entity vehicle, TeleportTransition transition) {
        for (Entity passenger : vehicle.getPassengers()) {
            var passengerTransition = ((VehicleTransitionAccess) vehicle)
                    .eventboundaries$passengerTransition(transition, passenger);
            if (passenger instanceof ServerPlayer player && EventBoundaries.rejectTeleport(player, passengerTransition)
                    || passenger.isVehicle() && deniesPassenger(passenger, passengerTransition)) {
                return true;
            }
        }
        return false;
    }

    @Mixin(Entity.class)
    public interface VehicleTransitionAccess {
        @Invoker("calculatePassengerTransition")
        TeleportTransition eventboundaries$passengerTransition(TeleportTransition transition, Entity passenger);
    }
}
