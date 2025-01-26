package com.chrisbesch.mcmissile.mixin;

import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
// import net.minecraft.world.explosion.Explosion;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.entity.projectile.ProjectileUtil;
// import net.minecraft.entity.data.DataTracker;
// import net.minecraft.item.ItemStack;
import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Math;

@Mixin(FireworkRocketEntity.class)
public abstract class MissileMixin extends ProjectileEntity implements FlyingItemEntity {
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // TODO: maybe make final or only create when needed
    private final Random random = Random.create();

    private int tickCount = 0;
    private int missileSelfDestructCount = 100;
    // set to true when this is detected
    private boolean isMissile = false;
    private int missileId;

    // this constructor is only needed to make the compiler happy
    public MissileMixin(EntityType<? extends ProjectileEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(at = @At("HEAD"), method = "tick()V", cancellable = true)
    private void tickInject(CallbackInfo info) {
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

        // determine if this is a missile
        if (tickCount == 0) {
            // TODO: determine
            // TODO: check is shot at angle
            // TODO: check name of rocket
            // TODO: check type of rocket
            this.isMissile = true;
        }

        // overwrite original tick method
        // TODO: check everything the original does is done here as well
        if (this.isMissile) {
            super.tick();
            LOGGER.info("missile tick {}", this.tickCount);

            // launch
            if (this.tickCount == 0) {
                LOGGER.info("missile launch");
                // thisObject.updateRotation(); is not needed as we set the rotation below
                if (!thisObject.isSilent()) {
                    LOGGER.info("sound");
                    thisObject.getWorld().playSound(null, thisObject.getX(), thisObject.getY(), thisObject.getZ(),
                            SoundEvents.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.AMBIENT, 20.0F, 1.0F);
                }

                this.missileId = this.random.nextInt();
                // TODO: establish connection
            }

            // entity collision check
            // we can always hit, this::canHit would be cleaner though
            HitResult hitResult = ProjectileUtil.getCollision(thisObject, e -> true);
            // block collision check
            thisObject.tickBlockCollision();

            // TODO: use data from connection
            double velX = 1.0D;
            double velY = 0.0D;
            double velZ = 0.0D;
            // TODO: maybe set velocity directly without normalizing and then scaling again
            float length = (float) Math.sqrt(velX*velX + velY*velY + velZ*velZ);
            thisObject.setVelocity(velX, velY, velZ, length, 0.0F);

            // TODO: check rotation is correctly set
            double posX = 0.0D;
            double posY = 160.0D;
            double posZ = 0.0D;
            float yaw = (float) (MathHelper.atan2(velX, velZ) * 180.0F / (float) Math.PI);
            float pitch = (float) (MathHelper.atan2(velY, Math.sqrt(velX * velX + velZ * velZ)) * 180.0F / (float) Math.PI);
            // maybe use setPosition instead
            thisObject.refreshPositionAndAngles(posX, posY, posZ, yaw, pitch);

            if (!thisObject.noClip && thisObject.isAlive() && hitResult.getType() != HitResult.Type.MISS) {
                thisObject.hitOrDeflect(hitResult);
                thisObject.velocityDirty = true;
            }

            // should detonate?
            if (this.tickCount >= this.missileSelfDestructCount && thisObject.getWorld() instanceof ServerWorld serverWorld) {
                thisObject.explodeAndRemove(serverWorld);
            }

            info.cancel();
        }
        ++tickCount;
    }

    // @Inject(at = @At("HEAD"), method = "onEntityHit(Lnet/minecraft/util/hit/EntityHitResult;)V", cancellable = true)
    // private void onEntityHitInject(EntityHitResult entityHitResult, CallbackInfo info) {
    //     LOGGER.info("missile entity hit");
    // }

    // @Inject(at = @At("HEAD"), method = "onBlockHit(Lnet/minecraft/util/hit/BlockHitResult;)V", cancellable = true)
    // private void onBlockHitInject(BlockHitResult blockHitResult, CallbackInfo info) {
    //     LOGGER.info("missile block hit");
    // }

    @Inject(at = @At("HEAD"), method = "explode(Lnet/minecraft/server/world/ServerWorld;)V", cancellable = true)
    private void explodeInject(ServerWorld world, CallbackInfo info) {
        LOGGER.info("missile explode");

        // TODO: only overwrite missile
        // TODO: stop default explode
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
        world.createExplosion(thisObject, thisObject.getX(), thisObject.getY(), thisObject.getZ(), 6, World.ExplosionSourceType.TNT);
        // world.createExplosion(thisObject, Explosion.createDamageSource(world, thisObject), thisObject.getX(), thisObject.getY(), thisObject.getZ(), 6, World.ExplosionSourceType.TNT);
    }

    // @Override
    // public void initDataTracker(DataTracker.Builder builder) {
    //     FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
    //     thisObject.initDataTracker(builder);
    // }

    // @Override
    // public ItemStack getStack() {
    //     FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
    //     return thisObject.getStack();
    // }
}
