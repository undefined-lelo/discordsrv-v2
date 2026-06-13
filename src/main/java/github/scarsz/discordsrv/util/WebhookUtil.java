/*
 * DiscordSRV - https://github.com/DiscordSRV/DiscordSRV
 *
 * Copyright (C) 2016 - 2024 Austin "Scarsz" Shapiro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package github.scarsz.discordsrv.util;

import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.emoji.CustomEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import okhttp3.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WebhookUtil {

    private static final Predicate<Webhook> LEGACY = hook -> hook.getName().endsWith("#1") || hook.getName().endsWith("#2");
    private static boolean loggedBannedWords = false;

    static {
        try {
            // get rid of all previous webhooks created by DiscordSRV if they don't match a good channel
            for (Guild guild : DiscordSRV.getPlugin().getJda().getGuilds()) {
                Member selfMember = guild.getSelfMember();
                if (!selfMember.hasPermission(Permission.MANAGE_WEBHOOKS)) {
                    DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Unable to manage webhooks guild-wide in " + guild);
                    continue;
                }

                guild.retrieveWebhooks().queue(webhooks -> {
                    for (Webhook webhook : webhooks) {
                        Member owner = webhook.getOwner();
                        if (owner == null || !owner.getId().equals(selfMember.getId()) || !webhook.getName().startsWith("DiscordSRV")) {
                            continue;
                        }

                        if (DiscordSRV.getPlugin().getDestinationGameChannelNameForTextChannel((TextChannel) webhook.getChannel()) == null) {
                            webhook.delete().reason("DiscordSRV: Purging webhook for unlinked channel").queue();
                        } else if (LEGACY.test(webhook)) {
                            webhook.delete().reason("DiscordSRV: Purging legacy formatted webhook").queue();
                        }
                    }
                });
            }
        } catch (Exception e) {
            DiscordSRV.warning("Failed to purge already existing webhooks: " + e.getMessage());
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, e);
        }
    }

    public static void deliverMessage(TextChannel channel, Player player, String message) {
        deliverMessage(channel, player, message, (Collection<? extends MessageEmbed>) null);
    }

    @SuppressWarnings("deprecation")
    public static void deliverMessage(TextChannel channel, Player player, String message, MessageEmbed embed) {
        deliverMessage(channel, player, player.getDisplayName(), message, embed);
    }

    @SuppressWarnings("deprecation")
    public static void deliverMessage(TextChannel channel, Player player, String message, Collection<? extends MessageEmbed> embeds) {
        deliverMessage(channel, player, player.getDisplayName(), message, embeds);
    }

    @SuppressWarnings("deprecation")
    public static void deliverMessage(TextChannel channel, Player player, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        deliverMessage(channel, player, player.getDisplayName(), message, embed, attachments, interactions);
    }

    @SuppressWarnings("deprecation")
    public static void deliverMessage(TextChannel channel, Player player, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        deliverMessage(channel, player, player.getDisplayName(), message, embeds, attachments, interactions);
    }

    public static void deliverMessage(TextChannel channel, OfflinePlayer player, String displayName, String message, MessageEmbed embed) {
        deliverMessage(channel, player, displayName, message, embed, null, null);
    }

    public static void deliverMessage(TextChannel channel, OfflinePlayer player, String displayName, String message, Collection<? extends MessageEmbed> embeds) {
        deliverMessage(channel, player, displayName, message, embeds, null, null);
    }

    public static void deliverMessage(TextChannel channel, OfflinePlayer player, String displayName, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        deliverMessage(channel, player, displayName, message, Collections.singletonList(embed), null, null);
    }

    public static void deliverMessage(TextChannel channel, OfflinePlayer player, String displayName, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(), () -> {
            String avatarUrl;
            if (player instanceof Player) {
                avatarUrl = DiscordSRV.getAvatarUrl((Player) player);
            } else {
                avatarUrl = DiscordSRV.getAvatarUrl(player.getName(), player.getUniqueId());
            }

            String username = DiscordSRV.config().getString("Experiment_WebhookChatMessageUsernameFormat")
                    .replace("%displayname%", displayName)
                    .replace("%username%", String.valueOf(player.getName()));
            String chatMessage = DiscordSRV.config().getString("Experiment_WebhookChatMessageFormat")
                    .replace("%displayname%", displayName)
                    .replace("%username%", player.getName())
                    .replace("%message%", message.replace("[", "\\["));
            chatMessage = PlaceholderUtil.replacePlaceholdersToDiscord(chatMessage, player);
            chatMessage = DiscordUtil.translateEmotes(chatMessage, channel.getGuild());
            username = PlaceholderUtil.replacePlaceholdersToDiscord(username, player);
            username = MessageUtil.strip(username);

            for (Map.Entry<Pattern, String> entry : DiscordSRV.getPlugin().getGameRegexes().entrySet()) {
                username = entry.getKey().matcher(username).replaceAll(entry.getValue());
                chatMessage = entry.getKey().matcher(chatMessage).replaceAll(entry.getValue());

                if (StringUtils.isBlank(username)) {
                    DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Not processing Minecraft message because the webhook username was cleared by a filter: " + entry.getKey().pattern());
                    return;
                }

                if (StringUtils.isBlank(chatMessage)) {
                    DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Not processing Minecraft message because the webhook content was cleared by a filter: " + entry.getKey().pattern());
                    return;
                }
            }

            String userId = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(player.getUniqueId());
            if (userId != null) {
                Member member = DiscordUtil.getMemberById(userId);
                username = username
                        .replace("%discordname%", member != null ? member.getEffectiveName() : "")
                        .replace("%discordusername%", member != null ? member.getUser().getName() : "");
                if (member != null) {
                    if (DiscordSRV.config().getBoolean("Experiment_WebhookChatMessageAvatarFromDiscord"))
                        avatarUrl = member.getUser().getEffectiveAvatarUrl();
                    if (DiscordSRV.config().getBoolean("Experiment_WebhookChatMessageUsernameFromDiscord"))
                        username = member.getEffectiveName();
                }
            } else {
                username = username
                        .replace("%discordname%", "")
                        .replace("%discordusername%", "");
            }

            if (username.length() > 80) {
                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "The webhook username in " + player.getName() + "'s message was too long! Reducing to 80 characters");
                username = username.substring(0, 80);
            }

            deliverMessage(channel, username, avatarUrl, chatMessage, embeds, attachments, interactions);
        });
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, MessageEmbed embed) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, Collections.singletonList(embed), null, null, true, true);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, MessageEmbed embed, boolean scheduleAsync) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, Collections.singletonList(embed), null, null, true, scheduleAsync);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, Collection<? extends MessageEmbed> embeds) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embeds, null, null, true, true);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, Collection<? extends MessageEmbed> embeds, boolean scheduleAsync) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embeds, null, null, true, scheduleAsync);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, Collections.singletonList(embed), attachments, interactions, true, true);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends Component> interactions, boolean scheduleAsync) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, Collections.singletonList(embed), attachments, interactions, true, scheduleAsync);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embeds, attachments, interactions, true, true);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions, boolean scheduleAsync) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embeds, attachments, interactions, true, scheduleAsync);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, MessageEmbed embed, Collection<? extends Component> components) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embed != null ? Collections.singletonList(embed) : null, null, components, true, true);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, MessageEmbed embed, Collection<? extends Component> components, boolean scheduleAsync) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embed != null ? Collections.singletonList(embed) : null, null, components, true, scheduleAsync);
    }

    public static void deliverMessage(TextChannel channel, String webhookName, String webhookAvatarUrl, String message, Collection<? extends MessageEmbed> embeds, Collection<? extends Component> components) {
        executeWebhook(channel, webhookName, webhookAvatarUrl, null, message, embeds, null, components, true, true);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, MessageEmbed embed) {
        executeWebhook(channel, null, null, editMessageId, message, Collections.singletonList(embed), null, null, true, true);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, MessageEmbed embed, boolean scheduleAsync) {
        executeWebhook(channel, null, null, editMessageId, message, Collections.singletonList(embed), null, null, true, scheduleAsync);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, Collection<? extends MessageEmbed> embeds) {
        executeWebhook(channel, null, null, editMessageId, message, embeds, null, null, true, true);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, Collection<? extends MessageEmbed> embeds, boolean scheduleAsync) {
        executeWebhook(channel, null, null, editMessageId, message, embeds, null, null, true, scheduleAsync);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        executeWebhook(channel, null, null, editMessageId, message, Collections.singletonList(embed), attachments, interactions, true, true);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends Component> interactions, boolean scheduleAsync) {
        executeWebhook(channel, null, null, editMessageId, message, Collections.singletonList(embed), attachments, interactions, true, scheduleAsync);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions) {
        executeWebhook(channel, null, null, editMessageId, message, embeds, attachments, interactions, true, true);
    }

    public static void editMessage(TextChannel channel, String editMessageId, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions, boolean scheduleAsync) {
        executeWebhook(channel, null, null, editMessageId, message, embeds, attachments, interactions, true, scheduleAsync);
    }

    private static void executeWebhook(TextChannel channel, String webhookName, String webhookAvatarUrl, String editMessageId, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends Component> interactions, boolean allowSecondAttempt, boolean scheduleAsync) {
        if (channel == null) {
            if (attachments != null) {
                attachments.values().forEach(inputStream -> {
                    try {
                        inputStream.close();
                    } catch (IOException ignore) {
                    }
                });
            }
            return;
        }

        String webhookUrlForChannel = getWebhookUrlToUseForChannel(channel);
        if (webhookUrlForChannel == null) {
            if (attachments != null) {
                attachments.values().forEach(inputStream -> {
                    try {
                        inputStream.close();
                    } catch (IOException ignore) {
                    }
                });
            }
            return;
        }

        if (editMessageId != null) {
            webhookUrlForChannel += "/messages/" + editMessageId;
        }
        String webhookUrl = webhookUrlForChannel;

        Runnable task = () -> {
            try {
                JSONObject jsonObject = new JSONObject();
                if (editMessageId == null) {
                    String webName = webhookName;
                    for (Map.Entry<Pattern, String> entry : DiscordSRV.getPlugin().getWebhookUsernameRegexes().entrySet()) {
                        webName = entry.getKey().matcher(webName).replaceAll(entry.getValue());
                    }

                    // Handle Discord banned words in a way that isn't against their developer policy
                    String username = webName;
                    username = username
                            .replaceAll("(?i)(cly)d(e)", "$1*$2")
                            .replaceAll("(?i)(d)i(scord)", "$1*$2");
                    if (!username.equals(webName) && loggedBannedWords) {
                        DiscordSRV.info("Some webhook usernames are being altered to remove blocked words (eg. Clyde and Discord)");
                        loggedBannedWords = true;
                    }

                    jsonObject.put("username", username);
                    jsonObject.put("avatar_url", webhookAvatarUrl);
                }

                if (StringUtils.isNotBlank(message)) jsonObject.put("content", message);
                if (embeds != null) {
                    JSONArray jsonArray = new JSONArray();
                    for (MessageEmbed embed : embeds) {
                        if (embed != null) {
                            jsonArray.put(embedToJson(embed));
                        }
                    }
                    jsonObject.put("embeds", jsonArray);
                }
                if (interactions != null) {
                    JSONArray jsonArray = new JSONArray();
                    for (Component component : interactions) {
                        jsonArray.put(componentToJson(component));
                    }
                    jsonObject.put("components", jsonArray);
                }
                List<String> attachmentIndex = null;
                if (attachments != null) {
                    attachmentIndex = new ArrayList<>(attachments.size());
                    JSONArray jsonArray = new JSONArray();
                    int i = 0;
                    for (String name : attachments.keySet()) {
                        attachmentIndex.add(name);
                        JSONObject attachmentObject = new JSONObject();
                        attachmentObject.put("id", i);
                        attachmentObject.put("filename", name);
                        jsonArray.put(attachmentObject);
                        i++;
                    }
                    jsonObject.put("attachments", jsonArray);
                }

                JSONObject allowedMentions = new JSONObject();
                Set<String> parse = DiscordSRV.config().getStringList("DiscordChatChannelAllowedMentions").stream()
                        .map(String::toUpperCase)
                        .map(mt -> {
                            try {
                                Message.MentionType.valueOf(mt);
                            } catch (IllegalArgumentException e) {
                                return null;
                            }
                            switch (mt) {
                                case "USER": return "users";
                                case "ROLES": return "roles";
                                case "EVERYONE": return "everyone";
                                case "HERE": return "here";
                                default: return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                allowedMentions.put("parse", parse);
                jsonObject.put("allowed_mentions", allowedMentions);

                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Sending webhook payload: " + jsonObject);

                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
                bodyBuilder.addFormDataPart("payload_json", null, RequestBody.create(MediaType.get("application/json"), jsonObject.toString()));

                if (attachmentIndex != null) {
                    for (int i = 0; i < attachmentIndex.size(); i++) {
                        String name = attachmentIndex.get(i);
                        InputStream data = attachments.get(name);
                        if (data != null) {
                            bodyBuilder.addFormDataPart("files[" + i + "]", name, RequestBody.create(MediaType.parse("application/octet-stream"), IOUtils.toByteArray(data)));
                            data.close();
                        }
                    }
                }

                Request.Builder requestBuilder = new Request.Builder().url(webhookUrl)
                        .header("User-Agent", "DiscordSRV/" + DiscordSRV.getPlugin().getDescription().getVersion());
                if (editMessageId == null) {
                    requestBuilder.post(bodyBuilder.build());
                } else {
                    requestBuilder.patch(bodyBuilder.build());
                }

                OkHttpClient httpClient = DiscordSRV.getPlugin().getHttpClient();
                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    int status = response.code();
                    if (status == 404) {
                        // 404 = Invalid Webhook (most likely to have been deleted)
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 404, marking webhooks URLs as invalid to let them regenerate" + (allowSecondAttempt ? " & trying again" : ""));
                        invalidWebhookUrlForChannel(channel); // tell it to get rid of the urls & get new ones
                        if (allowSecondAttempt)
                            executeWebhook(channel, webhookName, webhookAvatarUrl, editMessageId, message, embeds, attachments, interactions, false, scheduleAsync);
                        return;
                    }
                    String body = response.body().string();
                    try {
                        JSONObject jsonObj = new JSONObject(body);
                        if (jsonObj.has("code")) {
                            // 10015 = unknown webhook, https://discord.com/developers/docs/topics/opcodes-and-status-codes#json-json-error-codes
                            if (jsonObj.getInt("code") == 10015) {
                                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 10015 (Unknown Webhook), marking webhooks url's as invalid to let them regenerate" + (allowSecondAttempt ? " & trying again" : ""));
                                invalidWebhookUrlForChannel(channel); // tell it to get rid of the urls & get new ones
                                if (allowSecondAttempt)
                                    executeWebhook(channel, webhookName, webhookAvatarUrl, editMessageId, message, embeds, attachments, interactions, false, scheduleAsync);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (editMessageId == null ? status == 204 : status == 200) {
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Received API response for webhook message delivery: " + status);
                    } else {
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Received unexpected API response for webhook message delivery: " + status + " for request: " + jsonObject.toString() + ", response: " + body);
                    }
                }
            } catch (Exception e) {
                DiscordSRV.error("Failed to deliver webhook message to Discord: " + e.getMessage());
                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, e);
                if (attachments != null) {
                    attachments.values().forEach(inputStream -> {
                        try {
                            inputStream.close();
                        } catch (IOException ignore) {
                        }
                    });
                }
            }
        };

        if (scheduleAsync) {
            SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(), task);
        } else {
            task.run();
        }
    }

    private static final Map<String, String> channelWebhookUrls = new ConcurrentHashMap<>();

    public static void invalidWebhookUrlForChannel(TextChannel textChannel) {
        String channelId = textChannel.getId();
        channelWebhookUrls.remove(channelId);
    }

    public static String getWebhookUrlToUseForChannel(TextChannel channel) {
        final String channelId = channel.getId();
        return channelWebhookUrls.computeIfAbsent(channelId, cid -> {
            List<Webhook> hooks = new ArrayList<>();
            final Guild guild = channel.getGuild();
            final Member selfMember = guild.getSelfMember();

            String bannedWebhookFormat = "DiscordSRV " + cid; // This format is blocked by Discord
            String webhookFormat = "DSRV " + cid;

            // Check if we have permission guild-wide
            List<Webhook> result;
            if (guild.getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS)) {
                result = guild.retrieveWebhooks().complete();
            } else {
                result = channel.retrieveWebhooks().complete();
            }

            result.stream()
                    .filter(webhook -> webhook.getName().startsWith(webhookFormat) || webhook.getName().startsWith(bannedWebhookFormat))
                    .filter(webhook -> {
                        // Filter to what we can modify
                        Member owner = webhook.getOwner();
                        return owner != null && selfMember.getId().equals(owner.getId());
                    })
                    .filter(webhook -> {
                        if (!webhook.getChannel().equals(channel)) {
                            webhook.delete().reason("DiscordSRV: Purging lost webhook").queue();
                            return false;
                        }
                        return true;
                    })
                    .filter(webhook -> {
                        if (LEGACY.test(webhook)) {
                            webhook.delete().reason("DiscordSRV: Purging legacy formatted webhook").queue();
                            return false;
                        }
                        return true;
                    })
                    .forEach(hooks::add);

            if (hooks.isEmpty()) {
                hooks.add(createWebhook(channel, webhookFormat));
            } else if (hooks.size() > 1) {
                for (int index = 1; index < hooks.size(); index++) {
                    hooks.get(index).delete().reason("DiscordSRV: Purging duplicate webhook").queue();
                }
            }

            return hooks.stream().map(Webhook::getUrl).findAny().orElse(null);
        });
    }

    public static Webhook createWebhook(TextChannel channel, String name) {
        try {
            Webhook webhook = channel.createWebhook(name).reason("DiscordSRV: Creating webhook").complete();
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Created webhook " + webhook.getName() + " to deliver messages to text channel #" + channel.getName());
            return webhook;
        } catch (Exception e) {
            DiscordSRV.error("Failed to create webhook " + name + " for message delivery: " + e.getMessage());
            return null;
        }
    }

    public static String getWebhookUrlFromCache(TextChannel channel) {
        return channelWebhookUrls.get(channel.getId());
    }

    private static JSONObject embedToJson(MessageEmbed embed) {
        JSONObject json = new JSONObject();
        if (embed.getTitle() != null) json.put("title", embed.getTitle());
        if (embed.getDescription() != null) json.put("description", embed.getDescription());
        if (embed.getUrl() != null) json.put("url", embed.getUrl());
        if (embed.getTimestamp() != null) json.put("timestamp", embed.getTimestamp().toString());
        if (embed.getColorRaw() != 0) json.put("color", embed.getColorRaw());
        if (embed.getFooter() != null) {
            JSONObject footer = new JSONObject();
            footer.put("text", embed.getFooter().getText());
            if (embed.getFooter().getIconUrl() != null) footer.put("icon_url", embed.getFooter().getIconUrl());
            json.put("footer", footer);
        }
        if (embed.getImage() != null) {
            JSONObject image = new JSONObject();
            image.put("url", embed.getImage().getUrl());
            json.put("image", image);
        }
        if (embed.getThumbnail() != null) {
            JSONObject thumbnail = new JSONObject();
            thumbnail.put("url", embed.getThumbnail().getUrl());
            json.put("thumbnail", thumbnail);
        }
        if (embed.getAuthor() != null) {
            JSONObject author = new JSONObject();
            author.put("name", embed.getAuthor().getName());
            if (embed.getAuthor().getUrl() != null) author.put("url", embed.getAuthor().getUrl());
            if (embed.getAuthor().getIconUrl() != null) author.put("icon_url", embed.getAuthor().getIconUrl());
            json.put("author", author);
        }
        if (!embed.getFields().isEmpty()) {
            JSONArray fields = new JSONArray();
            for (MessageEmbed.Field field : embed.getFields()) {
                JSONObject fieldObj = new JSONObject();
                fieldObj.put("name", field.getName());
                fieldObj.put("value", field.getValue());
                fieldObj.put("inline", field.isInline());
                fields.put(fieldObj);
            }
            json.put("fields", fields);
        }
        return json;
    }

    private static JSONObject actionRowToJson(ActionRow row) {
        JSONObject json = new JSONObject();
        json.put("type", 1);
        JSONArray components = new JSONArray();
        for (Component component : row.getComponents()) {
            if (component instanceof Button) {
                components.put(buttonToJson((Button) component));
            } else if (component instanceof StringSelectMenu) {
                components.put(selectMenuToJson((StringSelectMenu) component));
            }
        }
        json.put("components", components);
        return json;
    }

    private static JSONObject buttonToJson(Button button) {
        JSONObject json = new JSONObject();
        json.put("type", 2);
        json.put("style", button.getStyle().getKey());
        if (button.getLabel() != null) json.put("label", button.getLabel());
        if (button.getEmoji() != null) {
            JSONObject emoji = new JSONObject();
            Emoji e = button.getEmoji();
            emoji.put("name", e.getName());
            if (e instanceof CustomEmoji) {
                CustomEmoji custom = (CustomEmoji) e;
                emoji.put("id", custom.getId());
                if (custom.isAnimated()) emoji.put("animated", true);
            }
            json.put("emoji", emoji);
        }
        if (button.getStyle() == ButtonStyle.LINK) {
            json.put("url", button.getUrl());
        } else {
            json.put("custom_id", button.getCustomId());
        }
        if (button.isDisabled()) json.put("disabled", true);
        return json;
    }

    private static JSONObject selectMenuToJson(StringSelectMenu menu) {
        JSONObject json = new JSONObject();
        json.put("type", 3);
        json.put("custom_id", menu.getCustomId());
        if (menu.getPlaceholder() != null) json.put("placeholder", menu.getPlaceholder());
        json.put("min_values", menu.getMinValues());
        json.put("max_values", menu.getMaxValues());
        if (menu.isDisabled()) json.put("disabled", true);
        JSONArray options = new JSONArray();
        for (SelectOption option : menu.getOptions()) {
            JSONObject opt = new JSONObject();
            opt.put("label", option.getLabel());
            opt.put("value", option.getValue());
            if (option.getDescription() != null) opt.put("description", option.getDescription());
            if (option.getEmoji() != null) {
                JSONObject emoji = new JSONObject();
                Emoji e = option.getEmoji();
                emoji.put("name", e.getName());
                if (e instanceof CustomEmoji) {
                    CustomEmoji custom = (CustomEmoji) e;
                    emoji.put("id", custom.getId());
                    if (custom.isAnimated()) emoji.put("animated", true);
                }
                opt.put("emoji", emoji);
            }
            if (option.isDefault()) opt.put("default", true);
            options.put(opt);
        }
        json.put("options", options);
        return json;
    }

    private static JSONObject componentToJson(Component component) {
        if (component instanceof ActionRow) {
            return actionRowToJson((ActionRow) component);
        } else if (component instanceof TextDisplay) {
            return textDisplayToJson((TextDisplay) component);
        } else if (component instanceof Section) {
            return sectionToJson((Section) component);
        } else if (component instanceof Container) {
            return containerToJson((Container) component);
        } else if (component instanceof Separator) {
            return separatorToJson((Separator) component);
        } else if (component instanceof Button) {
            return buttonToJson((Button) component);
        } else if (component instanceof Thumbnail) {
            return thumbnailToJson((Thumbnail) component);
        } else {
            return new JSONObject();
        }
    }

    private static JSONObject textDisplayToJson(TextDisplay display) {
        JSONObject json = new JSONObject();
        json.put("type", 11);
        json.put("content", display.getContent());
        return json;
    }

    private static JSONObject sectionToJson(Section section) {
        JSONObject json = new JSONObject();
        json.put("type", 12);
        JSONArray components = new JSONArray();
        for (net.dv8tion.jda.api.components.Component child : section.getContentComponents()) {
            components.put(componentToJson(child));
        }
        json.put("components", components);
        net.dv8tion.jda.api.components.Component accessory = section.getAccessory();
        if (accessory != null) {
            json.put("accessory", componentToJson(accessory));
        }
        return json;
    }

    private static JSONObject containerToJson(Container container) {
        JSONObject json = new JSONObject();
        json.put("type", 13);
        JSONArray components = new JSONArray();
        for (net.dv8tion.jda.api.components.Component child : container.getComponents()) {
            components.put(componentToJson(child));
        }
        json.put("components", components);
        Integer accentColor = container.getAccentColorRaw();
        if (accentColor != null) {
            json.put("accent_color", accentColor);
        }
        return json;
    }

    private static JSONObject separatorToJson(Separator separator) {
        JSONObject json = new JSONObject();
        json.put("type", 14);
        json.put("spacing", separator.getSpacing().ordinal());
        return json;
    }

    private static JSONObject thumbnailToJson(Thumbnail thumbnail) {
        JSONObject json = new JSONObject();
        json.put("type", 19);
        json.put("url", thumbnail.getUrl());
        return json;
    }

}
