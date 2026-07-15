package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

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
                plugin.repository().all().forEach(j -> sender.sendMessage("§e" + j.id() + " §7owner=" + j.owner()
                        + " state=" + (j.placed() ? "placed" : "item")
                        + " outside=" + j.world() + ":" + j.x() + "," + j.y() + "," + j.z()
                        + " cell=" + j.cell() + " scale=" + j.scale()
                        + " size=" + j.width() + "x" + j.height() + "x" + j.depth()
                        + " parts=" + j.parts().size()
                        + " portal=" + (j.hasPortal() ? j.doorX() + "," + j.doorY() + ","
                        + j.doorZ() + "," + j.door() : "none")));
            }
            case "reload" -> {
                if (!sender.hasPermission("worldinajar.reload")) return denied(sender);
                plugin.reloadPlugin(); sender.sendMessage("§aWorldInAJar configuration and records reloaded.");
            }
            case "exit" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!sender.hasPermission("worldinajar.exit")) return denied(sender);
                InteriorService.ExitResult result = plugin.interiors().exit(player, plugin.repository());
                if (result == InteriorService.ExitResult.NOT_INSIDE) return error(sender, "You are not inside a jar.");
                if (result == InteriorService.ExitResult.CLOGGED) return error(sender, "The jar door is clogged. You cannot leave.");
            }
            default -> sender.sendMessage("§6/jar create, /jar give <player>, /jar list, /jar reload, /jar exit");
        }
        return true;
    }

    private boolean denied(CommandSender sender) { return error(sender, "You do not have permission."); }
    private boolean error(CommandSender sender, String message) { sender.sendMessage("§c" + message); return true; }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("create", "give", "list", "reload", "exit").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        return List.of();
    }
}
