package net.coreprotect.command;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.bukkit.command.CommandSender;

import net.coreprotect.config.Config;
import net.coreprotect.services.RetentionService;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.HumanDuration;

/**
 * {@code /co retention [status|enable|disable|set <duration>|run]}.
 *
 * <p>The auto-retention sweeper is opt-in: existing operators see no behaviour
 * change unless they explicitly enable it. Live changes here update the
 * running service but do NOT persist back into config.yml — the next plugin
 * reload reads from disk again, so config remains the source of truth.</p>
 */
public final class RetentionCommand {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String PERM = "coreprotect.purge"; // share the purge permission

    private RetentionCommand() {}

    public static void runCommand(CommandSender sender, boolean permission, String[] args) {
        if (!permission) {
            Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- You do not have permission to do that.");
            return;
        }
        String sub = (args.length > 1 ? args[1].toLowerCase() : "status");
        RetentionService svc = RetentionService.get();
        switch (sub) {
            case "status": {
                long keep = svc.keepSeconds();
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Retention: "
                        + (svc.isEnabled() ? "enabled" : "disabled")
                        + ", keep=" + (keep > 0 ? humanize(keep) : "forever")
                        + ", schedule=" + Config.getGlobal().RETENTION_SCHEDULE);
                long last = svc.lastRunUnix();
                if (last > 0) {
                    Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Last sweep: "
                            + ISO.format(Instant.ofEpochSecond(last).atZone(ZoneOffset.UTC).toInstant()));
                }
                Instant nx = svc.nextFire(Instant.now());
                if (nx != null && svc.isEnabled()) {
                    Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Next sweep: " + ISO.format(nx));
                }
                return;
            }
            case "enable":  svc.setEnabled(true);  Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Retention enabled (live; persist via config.yml retention-enabled: true)."); return;
            case "disable": svc.setEnabled(false); Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Retention disabled (live)."); return;
            case "set": {
                if (args.length < 3) {
                    Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Usage: /co retention set <duration> (e.g. 30d, 8w, 6mo).");
                    return;
                }
                String spec = args[2];
                long s = HumanDuration.parseSeconds(spec);
                if (s <= 0) {
                    Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Invalid duration: '" + spec + "'.");
                    return;
                }
                svc.setKeep(spec);
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Retention keep set to " + humanize(s) + " (live).");
                return;
            }
            case "run":
            case "now": {
                if (svc.keepSeconds() <= 0) {
                    Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Retention keep is 0; nothing to do.");
                    return;
                }
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Running retention sweep…");
                Thread t = new Thread(() -> {
                    RetentionService.Summary summary = svc.runNow();
                    Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + summary);
                }, "CoreProtect-retention-runNow");
                t.setDaemon(true);
                t.start();
                return;
            }
            default:
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- Usage: /co retention [status|enable|disable|set <duration>|run]");
        }
    }

    private static String humanize(long s) {
        if (s % 86400 == 0)  return (s / 86400)  + "d";
        if (s % 3600 == 0)   return (s / 3600)   + "h";
        if (s % 60 == 0)     return (s / 60)     + "m";
        return s + "s";
    }
}
