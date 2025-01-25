package com.chrisbesch.mcmissile.mixin;

import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
// import net.minecraft.world.explosion.Explosion;
import net.minecraft.util.math.MathHelper;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// import org.spongepowered.asm.mixin.gen.Accessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Math;

@Mixin(FireworkRocketEntity.class)
public class MissileMixin {
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // @Accessor("life")
    // public void setLife(int life);

    @Inject(at = @At("HEAD"), method = "tick()V", cancellable = true)
    private void tickInject(CallbackInfo info) {
        LOGGER.info("missile tick");
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

        double velX = 1.0D;
        double velY = 0.0D;
        double velZ = 0.0D;
        // TODO: maybe set velocity directly without normalizing and then scaling again
        float length = (float) Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        thisObject.setVelocity(velX, velY, velZ, length, 0.0F);

        double posX = 0.0D;
        double posY = 160.0D;
        double posZ = 0.0D;
        float yaw = (float) (MathHelper.atan2(velX, velZ) * 180.0F / (float) Math.PI);
        float pitch = (float) (MathHelper.atan2(velY, Math.sqrt(velX * velX + velZ * velZ)) * 180.0F / (float) Math.PI);
        thisObject.refreshPositionAndAngles(posX, posY, posZ, yaw, pitch);
        // setLife(3);
        // thisObject.life = 3;
        // info.cancel();
    }

    @Inject(at = @At("HEAD"), method = "onEntityHit(Lnet/minecraft/util/hit/EntityHitResult;)V", cancellable = true)
    private void onEntityHitInject(EntityHitResult entityHitResult, CallbackInfo info) {
        LOGGER.info("missile entity hit");
    }

    @Inject(at = @At("HEAD"), method = "onBlockHit(Lnet/minecraft/util/hit/BlockHitResult;)V", cancellable = true)
    private void onBlockHitInject(BlockHitResult blockHitResult, CallbackInfo info) {
        LOGGER.info("missile block hit");
    }

    @Inject(at = @At("HEAD"), method = "explode(Lnet/minecraft/server/world/ServerWorld;)V", cancellable = true)
    private void explodeInject(ServerWorld world, CallbackInfo info) {
        LOGGER.info("missile explode");

        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
        world.createExplosion(thisObject, thisObject.getX(), thisObject.getY(), thisObject.getZ(), 6, World.ExplosionSourceType.TNT);
        // world.createExplosion(thisObject, Explosion.createDamageSource(world, thisObject), thisObject.getX(), thisObject.getY(), thisObject.getZ(), 6, World.ExplosionSourceType.TNT);
    }
}
