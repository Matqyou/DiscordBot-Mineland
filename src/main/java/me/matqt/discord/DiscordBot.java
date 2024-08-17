package me.matqt.discord;

import me.matqt.discord.listener.SpamFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class DiscordBot {
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(16);
    public static enum BotCommand {
        SPAM_FILTER
    }
    public static Map<BotCommand, String> commandNames;

    public static void main(String[] arguments) throws Exception
    {
        // Renaming commands might be necessary later
        commandNames = new HashMap<>();
        commandNames.put(BotCommand.SPAM_FILTER, "spamfilter");

        // Creating bot instance
        JDA api = JDABuilder.createDefault(DISCORD_BOT_TOKEN)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .build();

        // Register listeners and commands
        api.addEventListener(new SpamFilter());
        api.updateCommands().addCommands(
                Commands.slash(commandNames.get(BotCommand.SPAM_FILTER), "Filter chat for spam.")
                        .addOption(OptionType.STRING, "state", "Enable/disable the spam filter", true, true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL))
        ).queue();
    }

    public static String get_command_name(BotCommand command) {
        return commandNames.get(command);
    }
}

