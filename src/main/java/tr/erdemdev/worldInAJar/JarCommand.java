package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class JarCommand implements CommandExecutor, TabCompleter {
    private final WorldInAJar plugin;

    public JarCommand(WorldInAJar plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!sender.hasPermission("worldinajar.create")) return denied(sender);
                player.getInventory().addItem(plugin.items().create(null));
                sender.sendMessage("§aCreated a blank World Jar.");
            }
            case "give" -> {
                if (!sender.hasPermission("worldinajar.give")) return denied(sender);
                if (args.length < 2) return error(sender, "Usage: /jar give <player>");
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) return error(sender, "Player is not online.");
                target.getInventory().addItem(plugin.items().create(null));
                sender.sendMessage("§aGave a World Jar to " + target.getName() + ".");
            }
            case "list" -> {
                if (!sender.hasPermission("worldinajar.list")) return denied(sender);
                sender.sendMessage("§6World jars (" + plugin.repository().all().size() + "):");
                plugin.repository().all().forEach(j -> {
                    String name = plugin.repository().name(j.id());
                    sender.sendMessage("§e" + j.id() + (name == null ? "" : " §f\"" + name + "\"")
                            + " §7owner=" + j.owner()
                            + " state=" + (j.placed() ? "placed" : "item")
                            + " outside=" + j.world() + ":" + j.x() + "," + j.y() + "," + j.z()
                            + " cell=" + j.cell() + " scale=" + j.scale()
                            + " size=" + j.width() + "x" + j.height() + "x" + j.depth()
                            + " parts=" + j.parts().size()
                            + " portal=" + (j.hasPortal() ? j.doorX() + "," + j.doorY() + ","
                            + j.doorZ() + "," + j.door() : "none"));
                });
            }
            case "reload" -> {
                if (!sender.hasPermission("worldinajar.reload")) return denied(sender);
                plugin.reloadPlugin(); sender.sendMessage("§aWorldInAJar configuration and records reloaded.");
            }
            case "exit" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!sender.hasPermission("worldinajar.exit")) return denied(sender);
                if (plugin.spectators().hasRecovery(player)) {
                    plugin.spectators().restore(player);
                    return true;
                }
                InteriorService.ExitResult result = plugin.interiors().exit(player, plugin.repository());
                if (result == InteriorService.ExitResult.NOT_INSIDE) return error(sender, "You are not inside a jar.");
                if (result == InteriorService.ExitResult.CLOGGED) return error(sender, "The jar door is clogged. You cannot leave.");
            }
            case "back" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!sender.hasPermission("worldinajar.back")) return denied(sender);
                plugin.back().open(player);
            }
            case "admin" -> {
                if (!sender.hasPermission("worldinajar.admin")) return denied(sender);
                return admin(sender, args);
            }
            default -> sender.sendMessage("§6/jar create, /jar give <player>, /jar list, /jar reload, "
                    + "/jar exit, /jar back, /jar admin <info|enter|exit>");
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        String sub = args.length < 2 ? "" : args[1].toLowerCase();
        switch (sub) {
            case "info" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                JarRecord jar = currentJar(player);
                if (jar == null) return error(sender, "You are not inside a jar.");
                String name = plugin.repository().name(jar.id());
                UUID lastHolder = plugin.repository().lastHolder(jar.id());
                var owner = Bukkit.getOfflinePlayer(jar.owner());
                sender.sendMessage("§6Jar " + (name == null ? "(unnamed)" : "\"" + name + "\""));
                sender.sendMessage("§7Id: §e" + jar.id());
                sender.sendMessage("§7Owner: §e" + (owner.getName() == null ? jar.owner() : owner.getName()));
                sender.sendMessage("§7Last holder: §e" + (lastHolder == null ? "none"
                        : lastHolderName(lastHolder)));
                sender.sendMessage("§7State: §e" + (jar.placed() ? "placed at " + jar.world()
                        + " " + jar.x() + "," + jar.y() + "," + jar.z() : "carried as an item"));
                sender.sendMessage("§7Size: §e" + jar.width() + "x" + jar.height() + "x" + jar.depth()
                        + " §7scale: §e" + jar.scale() + " §7cell: §e" + jar.cell());
                sender.sendMessage("§7Respawn point: §e" + (plugin.repository()
                        .respawn(jar.id(), plugin.interiors().world()) == null ? "not set" : "set"));
            }
            case "enter" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 3) return error(sender, "Usage: /jar admin enter <jar id or name>");
                JarRecord jar = resolveJar(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                if (jar == null) return error(sender, "No jar matches that id or name.");
                if (!plugin.interiors().forceEnter(player, jar)) {
                    return error(sender, "That jar world is not ready yet. Please wait.");
                }
                sender.sendMessage("§aEntered jar " + describe(jar) + ".");
            }
            case "exit" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (plugin.spectators().hasRecovery(player)) {
                    plugin.spectators().restore(player);
                    return true;
                }
                if (player.getWorld() != plugin.interiors().world()) {
                    return error(sender, "You are not inside a jar.");
                }
                JarRecord jar = currentJar(player);
                Location destination = jar == null
                        ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                        : plugin.transfers().realWorldAnchor(jar);
                if (!plugin.policy().teleport(player, destination)) {
                    return error(sender, "Could not teleport you out of the jar.");
                }
                plugin.interiors().forget(player);
                sender.sendMessage("§aYou were forced out of the jar.");
            }
            default -> sender.sendMessage("§6/jar admin info, /jar admin enter <jar id or name>, /jar admin exit");
        }
        return true;
    }

    private JarRecord currentJar(Player player) {
        return plugin.interiors().syncSession(player, player.getLocation(), plugin.repository());
    }

    private JarRecord resolveJar(String query) {
        try {
            return plugin.repository().byId(UUID.fromString(query)).orElse(null);
        } catch (IllegalArgumentException ignored) {
            return plugin.repository().byName(query).orElse(null);
        }
    }

    private String describe(JarRecord jar) {
        String name = plugin.repository().name(jar.id());
        return name == null ? jar.id().toString() : "\"" + name + "\"";
    }

    private String lastHolderName(UUID lastHolder) {
        var holder = Bukkit.getOfflinePlayer(lastHolder);
        return holder.getName() == null ? lastHolder.toString() : holder.getName();
    }

    private boolean denied(CommandSender sender) { return error(sender, "You do not have permission."); }
    private boolean error(CommandSender sender, String message) { sender.sendMessage("§c" + message); return true; }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("create", "give", "list", "reload", "exit", "back"));
            if (sender.hasPermission("worldinajar.admin")) subs.add("admin");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("worldinajar.admin")) {
            if (args.length == 2) {
                return List.of("info", "enter", "exit").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("enter")) {
                List<String> suggestions = new ArrayList<>(plugin.repository().names());
                plugin.repository().all().forEach(jar -> suggestions.add(jar.id().toString()));
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
            }
        }
        return List.of();
    }
}
