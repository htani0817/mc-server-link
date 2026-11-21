package jp.mc.serverlink.server;

import jp.mc.serverlink.McServerLink;

/**
 * Youer など Bukkit の入ったサーバー向けの手当て。Bukkit が無ければ何もしない。
 * <p>
 * Youer は Mod が登録したコマンドも Bukkit の権限「minecraft.command.&lt;名前&gt;」で調べる。
 * Bukkit に登録されていない権限は「OP だけ」の扱いになるため、そのままだと OP でないプレイヤーは
 * /server・/lobby を使えない（「不明または不完全なコマンド」になる）。
 * そこで、全員が使ってよいコマンドの権限を「既定で許可」として登録する。
 * LuckPerms などで明示的に禁止すれば、そちらが優先される。
 * <p>
 * Bukkit の API はこの Mod のビルドに入れていないので、名前で呼ぶ。
 */
final class BukkitCompat {
    private BukkitCompat() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void allowForEveryone(String... commands) {
        Class<?> bukkit;
        try {
            bukkit = Class.forName("org.bukkit.Bukkit");
        } catch (ClassNotFoundException e) {
            return; // 純正の NeoForge
        }
        try {
            Object pm = bukkit.getMethod("getPluginManager").invoke(null);
            Class<?> permCls = Class.forName("org.bukkit.permissions.Permission");
            Class<? extends Enum> defCls = (Class<? extends Enum>) Class.forName("org.bukkit.permissions.PermissionDefault");
            Object allow = Enum.valueOf(defCls, "TRUE");
            for (String c : commands) {
                String name = "minecraft.command." + c;
                Object existing = pm.getClass().getMethod("getPermission", String.class).invoke(pm, name);
                if (existing != null) {
                    permCls.getMethod("setDefault", defCls).invoke(existing, allow);
                } else {
                    Object perm = permCls.getConstructor(String.class, String.class, defCls)
                            .newInstance(name, "mc-server-link: /" + c, allow);
                    pm.getClass().getMethod("addPermission", permCls).invoke(pm, perm);
                }
                McServerLink.LOGGER.info("[mc-server-link] Bukkit の権限 {} を全員に許可しました（既定値）", name);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            McServerLink.LOGGER.warn("[mc-server-link] Bukkit の権限を登録できませんでした。OP でないプレイヤーが /server を使うには、"
                    + "権限プラグインで minecraft.command.server と minecraft.command.lobby を許可してください", e);
        }
    }
}
