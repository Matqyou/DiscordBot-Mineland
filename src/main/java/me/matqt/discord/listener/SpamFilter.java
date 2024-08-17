package me.matqt.discord.listener;

import me.matqt.discord.DiscordBot;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SpamFilter extends ListenerAdapter {
    private final List<String> state_options_str = Arrays.asList("enable", "disable");
    private final Map<String, Map<String, List<OffsetDateTime>>> channel_rate_timestamps;
    private final Map<String, OffsetDateTime> user_warnings;

    public SpamFilter() {
        // { channel_id: { user_id: [ msg_timestamp1, msg_timestamp2, ... ], user_id2: [ ... ] }, channel_id2: [ ... ] }
        channel_rate_timestamps = new HashMap<String, Map<String, List<OffsetDateTime>>>();

        // { user_id: warning_timestamp, user_id2: ... }
        user_warnings = new HashMap<String, OffsetDateTime>();
    }

    public void updateUserWarning(String user_id) {
        user_warnings.put(user_id, OffsetDateTime.now());
    }

    public boolean wasWarnedRecently(String user_id) {
        long WARNING_RATE = 8000; // Time between each warning for a user

        OffsetDateTime timestamp_warning = user_warnings.get(user_id);
        if (timestamp_warning != null) {
            OffsetDateTime timestamp_now = OffsetDateTime.now();
            Duration warned_ago = Duration.between(timestamp_warning, timestamp_now);
            if (warned_ago.toMillis() <= WARNING_RATE) return true;
            else user_warnings.remove(user_id);
        }

        return false;
    }

    public boolean setSpamFilterChannel(String channel_id, boolean state) {
        boolean channel_already_filtered = channel_rate_timestamps.containsKey(channel_id);
        if (state && !channel_already_filtered) { // Enable spam filter && is not already moderated
            channel_rate_timestamps.put(channel_id, new HashMap<String, List<OffsetDateTime>>());
            return true;
        } else if (!state && channel_already_filtered) { // Disable spam filter && is already moderated
            channel_rate_timestamps.remove(channel_id);
            return true;
        }

        // No changes were made
        return false;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String channel_id = event.getChannel().getId();
        String author_id = event.getAuthor().getId();

        // If the channel is being moderated with spam filter
        Map<String, List<OffsetDateTime>> current_channel = channel_rate_timestamps.get(channel_id);
        if (current_channel == null) return;

        List<OffsetDateTime> message_history = current_channel.get(author_id);
        if (message_history == null) {
            // User did not chat recently, create history storage for them
            current_channel.put(author_id, new ArrayList<OffsetDateTime>());
            return;
        }
        OffsetDateTime timestamp_message = event.getMessage().getTimeCreated();

        // Define a rate limit for spam filter: 3messages/7seconds
        long TIMESPAN_MILLISECONDS = 7000;
        int MESSAGE_COUNT_LIMIT = 3;

        // Calculate message amount in the last 5000ms
        int message_count = 1; // User sent a message already
        OffsetDateTime timestamp_now = OffsetDateTime.now();
        List<OffsetDateTime> updated_history = new ArrayList<OffsetDateTime>();
        for (OffsetDateTime timestamp_old : message_history) {
            // Ignore messages sent too long ago
            Duration sent_ago = Duration.between(timestamp_old, timestamp_now);
            if (sent_ago.toMillis() <= TIMESPAN_MILLISECONDS) {
                updated_history.add(timestamp_old);
                message_count++;
            }
        }

        current_channel.put(author_id, updated_history);

        // Add message to history for future moderation
        boolean filter_message = message_count >= MESSAGE_COUNT_LIMIT;
        if (!filter_message) {
            updated_history.add(timestamp_message);
            return;
        }

        // Delete messages sent too quickly
        event.getMessage().delete().queue();

        // Warning cool down timer to avoid bot spamming warnings
        if (wasWarnedRecently(author_id)) return;
        updateUserWarning(author_id);

        // Create a warning message
        String mention_author = event.getAuthor().getAsMention();
        String reply_message = String.format("Please do not spam %s, thank you \uD83D\uDE2E", mention_author);

        // Send reply and delete after 2s
        MessageCreateAction bot_reply = event.getChannel().sendMessage(reply_message);
        bot_reply.queue(message -> {
            DiscordBot.scheduler.schedule(() -> {
                message.delete().queue();
            }, 5, TimeUnit.SECONDS);
        });
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals(DiscordBot.get_command_name(DiscordBot.BotCommand.SPAM_FILTER))) {
            String state_input = event.getOption("state").getAsString().toLowerCase();

            String reply_message;
            if (!state_options_str.contains(state_input)) { // Input validation fails
                // Build a syntax error reply
                StringBuilder invalid_state_message = new StringBuilder();
                invalid_state_message.append(String.format("Unknown state argument: `%s` \uD83E\uDD14", state_input));
                invalid_state_message.append("\n\nAvaliable options:");
                for (String option : state_options_str) invalid_state_message.append(String.format("\n- *%s*", option));

                // Send reply
                event.reply(invalid_state_message.toString()).setEphemeral(true).queue();
                return;
            }

            String channel_id = event.getChannelId();
            String channel_name = String.format("<#%s>", channel_id);

            // Enabling/disabling spam filter for channel_id
            if (state_input.equals("enable")) {
                boolean success = setSpamFilterChannel(channel_id, true);
                reply_message = success ? String.format("Spam filter has been enabled for %s \u2705", channel_name) :
                        String.format("Spam filter is already enabled for %s \u274C", channel_name);
            } else if (state_input.equals("disable")) {
                boolean success = setSpamFilterChannel(channel_id, false);
                reply_message = success ? String.format("Spam filter has been disabled for %s \u2705", channel_name) :
                        String.format("Spam filter is already disabled for %s \u274C", channel_name);
            } else { // Should never reach this line
                reply_message = "Error while processing command. \uD83E\uDD37";
            }

            // Send reply, .setEphemeral(true) hides the reply from other users
            event.reply(reply_message).setEphemeral(true).queue();
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        // Show command argument options
        if (event.getName().equals(DiscordBot.get_command_name(DiscordBot.BotCommand.SPAM_FILTER)) &&
                event.getFocusedOption().getName().equals("state")) {
            List<Command.Choice> options = state_options_str.stream()
                    .filter(word -> word.startsWith(event.getFocusedOption().getValue()))
                    .map(word -> new Command.Choice(word, word))
                    .collect(Collectors.toList());

            // Send options
            event.replyChoices(options).queue();
        }
    }
}
