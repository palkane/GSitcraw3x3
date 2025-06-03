package dev.geco.gsit.mcv.v1_21.object;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.object.GStopReason;
import dev.geco.gsit.object.IGCrawl;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;

public class GCrawl implements IGCrawl {

    private final GSitMain gSitMain = GSitMain.getInstance();
    private final Player player;
    private final ServerPlayer serverPlayer;
    protected final List<BoxEntity> boxEntities = new ArrayList<>(9);
    private final boolean[] boxEntitiesExist = new boolean[9];
    private Location blockLocation;
    protected final BlockData blockData = Material.BARRIER.createBlockData();
    private final Listener listener;
    private final Listener moveListener;
    private final Listener stopListener;
    private boolean finished = false;
    private final long spawnTime = System.nanoTime();

    public GCrawl(Player player) {
        this.player = player;
        this.serverPlayer = ((CraftPlayer) player).getHandle();

        // Initialize 9 box entities in a 3x3 grid
        for (int i = 0; i < 9; i++) {
            boxEntities.add(new BoxEntity(player.getLocation()));
            boxEntitiesExist[i] = false;
        }

        listener = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void entityToggleSwimEvent(EntityToggleSwimEvent event) {
                if(event.getEntity() == player) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void playerInteractEvent(PlayerInteractEvent event) {
                if(event.isAsynchronous() || event.getPlayer() != player || 
                   blockLocation == null || !blockLocation.getBlock().equals(event.getClickedBlock()) || 
                   event.getHand() != EquipmentSlot.HAND) return;
                event.setCancelled(true);
                gSitMain.getTaskService().run(() -> {
                    if(!finished) buildBlock(blockLocation);
                }, false, player);
            }
        };

        moveListener = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void playerMoveEvent(PlayerMoveEvent event) {
                if(event.isAsynchronous() || event.getPlayer() != player) return;
                Location fromLocation = event.getFrom(), toLocation = event.getTo();
                if(fromLocation.getX() != toLocation.getX() || fromLocation.getZ() != toLocation.getZ() || 
                   fromLocation.getY() != toLocation.getY()) tick(toLocation);
            }
        };

        stopListener = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void playerToggleSneakEvent(PlayerToggleSneakEvent event) {
                if(!event.isAsynchronous() && event.getPlayer() == player && event.isSneaking()) {
                    gSitMain.getCrawlService().stopCrawl(GCrawl.this, GStopReason.GET_UP);
                }
            }
        };
    }

    @Override
    public void start() {
        player.setSwimming(true);

        Bukkit.getPluginManager().registerEvents(listener, gSitMain);

        gSitMain.getTaskService().runDelayed(() -> {
            Bukkit.getPluginManager().registerEvents(moveListener, gSitMain);
            if(gSitMain.getConfigService().C_GET_UP_SNEAK) {
                Bukkit.getPluginManager().registerEvents(stopListener, gSitMain);
            }
            tick(player.getLocation());
        }, false, player, 1);
    }

    private void tick(Location location) {
        if(finished || !checkCrawlValid()) return;

        Location tickLocation = location.clone();
        Block locationBlock = tickLocation.getBlock();
        int blockSize = (int) ((tickLocation.getY() - tickLocation.getBlockY()) * 100);
        tickLocation.setY(tickLocation.getBlockY() + (blockSize >= 40 ? 2.49 : 1.49));
        Block aboveBlock = tickLocation.getBlock();
        boolean hasSolidBlockAbove = aboveBlock.getBoundingBox().contains(tickLocation.toVector()) && 
                                  !aboveBlock.getCollisionShape().getBoundingBoxes().isEmpty();
        boolean canPlaceBlock = isValidArea(locationBlock.getRelative(BlockFace.UP), aboveBlock, 
                                          blockLocation != null ? blockLocation.getBlock() : null);
        boolean canSetBarrier = canPlaceBlock && (aboveBlock.getType().isAir() || hasSolidBlockAbove);

        if(blockLocation == null || !aboveBlock.equals(blockLocation.getBlock())) {
            destoryBlock();
            if(canSetBarrier && !hasSolidBlockAbove) {
                buildBlock(tickLocation);
                return;
            }
        }

        if(canSetBarrier || hasSolidBlockAbove) {
            destoryAllEntities();
            return;
        }

        Location playerLocation = location.clone();
        gSitMain.getTaskService().run(() -> {
            if(finished) return;

            int height = locationBlock.getBoundingBox().getHeight() >= 0.4 || 
                        playerLocation.getY() % 0.015625 == 0.0 ? 
                        (player.getFallDistance() > 0.7 ? 0 : blockSize) : 0;

            playerLocation.setY(playerLocation.getY() + (height >= 40 ? 1.5 : 0.5));

            // Update all 9 entities in 3x3 grid
            for (int i = 0; i < 9; i++) {
                BoxEntity boxEntity = boxEntities.get(i);
                boxEntity.setRawPeekAmount(height >= 40 ? 100 - height : 0);

                // Calculate 3x3 grid positions
                double offsetX = (i % 3 - 1) * 0.3;  // -0.3, 0, +0.3
                double offsetZ = (i / 3 - 1) * 0.3;  // -0.3, 0, +0.3
                double x = playerLocation.getX() + offsetX;
                double y = playerLocation.getY();
                double z = playerLocation.getZ() + offsetZ;

                if (!boxEntitiesExist[i]) {
                    boxEntity.setPos(x, y, z);
                    serverPlayer.connection.send(new ClientboundAddEntityPacket(
                        boxEntity.getId(), boxEntity.getUUID(), x, y, z, 
                        boxEntity.getXRot(), boxEntity.getYRot(), boxEntity.getType(), 
                        0, boxEntity.getDeltaMovement(), boxEntity.getYHeadRot()));
                    boxEntitiesExist[i] = true;
                    serverPlayer.connection.send(new ClientboundSetEntityDataPacket(
                        boxEntity.getId(), boxEntity.getEntityData().getNonDefaultValues()));
                } else {
                    serverPlayer.connection.send(new ClientboundSetEntityDataPacket(
                        boxEntity.getId(), boxEntity.getEntityData().getNonDefaultValues()));
                    boxEntity.teleportTo(x, y, z);
                    serverPlayer.connection.send(new ClientboundTeleportEntityPacket(boxEntity));
                }
            }
        }, true, playerLocation);
    }

    @Override
    public void stop() {
        finished = true;

        HandlerList.unregisterAll(listener);
        HandlerList.unregisterAll(moveListener);
        HandlerList.unregisterAll(stopListener);

        player.setSwimming(false);

        destoryBlock();
        destoryAllEntities();
    }

    private void buildBlock(Location location) {
        blockLocation = location;
        if(blockLocation != null) player.sendBlockChange(blockLocation, blockData);
    }

    private void destoryBlock() {
        if(blockLocation == null) return;
        player.sendBlockChange(blockLocation, blockLocation.getBlock().getBlockData());
        blockLocation = null;
    }

    private void destoryAllEntities() {
        for (int i = 0; i < 9; i++) {
            if (boxEntitiesExist[i]) {
                serverPlayer.connection.send(new ClientboundRemoveEntitiesPacket(boxEntities.get(i).getId()));
                boxEntitiesExist[i] = false;
            }
        }
    }

    private boolean checkCrawlValid() {
        if(serverPlayer.isInWater() || player.isFlying()) {
            gSitMain.getCrawlService().stopCrawl(this, GStopReason.ENVIRONMENT);
            return false;
        }
        return true;
    }

    private boolean isValidArea(Block blockUp, Block aboveBlock, Block locationBlock) {
        return blockUp.equals(aboveBlock) || blockUp.equals(locationBlock);
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public long getLifetimeInNanoSeconds() {
        return System.nanoTime() - spawnTime;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (BoxEntity entity : boxEntities) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entity.getUUID().toString());
        }
        return sb.toString();
    }
}
