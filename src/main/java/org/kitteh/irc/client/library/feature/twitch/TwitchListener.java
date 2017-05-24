/*
 * * Copyright (C) 2013-2017 Matt Baxter http://kitteh.org
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.kitteh.irc.client.library.feature.twitch;

import net.engio.mbassy.listener.Handler;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.CapabilityState;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.MessageTag;
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesSupportedListEvent;
import org.kitteh.irc.client.library.event.client.ClientReceiveCommandEvent;
import org.kitteh.irc.client.library.exception.KittehServerMessageException;
import org.kitteh.irc.client.library.feature.filter.CommandFilter;
import org.kitteh.irc.client.library.feature.twitch.event.ClearChatEvent;
import org.kitteh.irc.client.library.feature.twitch.event.GlobalUserStateEvent;
import org.kitteh.irc.client.library.feature.twitch.event.RoomStateEvent;
import org.kitteh.irc.client.library.feature.twitch.event.UserNoticeEvent;
import org.kitteh.irc.client.library.feature.twitch.event.UserStateEvent;
import org.kitteh.irc.client.library.feature.twitch.messagetag.BanDuration;
import org.kitteh.irc.client.library.feature.twitch.messagetag.BanReason;
import org.kitteh.irc.client.library.feature.twitch.messagetag.Color;
import org.kitteh.irc.client.library.feature.twitch.messagetag.DisplayName;
import org.kitteh.irc.client.library.feature.twitch.messagetag.EmoteSets;
import org.kitteh.irc.client.library.feature.twitch.messagetag.MsgId;
import org.kitteh.irc.client.library.feature.twitch.messagetag.Turbo;
import org.kitteh.irc.client.library.feature.twitch.messagetag.UserId;
import org.kitteh.irc.client.library.feature.twitch.messagetag.UserType;
import org.kitteh.irc.client.library.util.Sanity;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helpful things.
 */
public class TwitchListener {
    /**
     * Capability to receive commands.
     */
    public static final String CAPABILITY_COMMANDS = "twitch.tv/commands";

    /**
     * Capability to receive JOIN, MODE, NAMES, and PART.
     */
    public static final String CAPABILITY_MEMBERSHIP = "twitch.tv/membership";

    /**
     * Capability to receive tags.
     */
    public static final String CAPABILITY_TAGS = "twitch.tv/tags";

    private final Client client;

    public TwitchListener(@Nonnull Client client) {
        this.client = Sanity.nullCheck(client, "Client cannot be null");
        client.getMessageTagManager().registerTagCreator("ban-duration", CAPABILITY_TAGS, BanDuration.FUNCTION);
        client.getMessageTagManager().registerTagCreator("ban-reason", CAPABILITY_TAGS, BanReason.FUNCTION);
        client.getMessageTagManager().registerTagCreator("color", CAPABILITY_TAGS, Color.FUNCTION);
        client.getMessageTagManager().registerTagCreator("display-name", CAPABILITY_TAGS, DisplayName.FUNCTION);
        client.getMessageTagManager().registerTagCreator("emote-sets", CAPABILITY_TAGS, EmoteSets.FUNCTION);
        client.getMessageTagManager().registerTagCreator("msg-id", CAPABILITY_TAGS, MsgId.FUNCTION);
        client.getMessageTagManager().registerTagCreator("turbo", CAPABILITY_TAGS, Turbo.FUNCTION);
        client.getMessageTagManager().registerTagCreator("user-id", CAPABILITY_TAGS, UserId.FUNCTION);
        client.getMessageTagManager().registerTagCreator("user-type", CAPABILITY_TAGS, UserType.FUNCTION);
    }

    @Handler
    public void capList(@Nonnull CapabilitiesSupportedListEvent event) {
        List<String> already = this.client.getCapabilityManager().getCapabilities().stream().map(CapabilityState::getName).collect(Collectors.toList());
        if (!already.contains(CAPABILITY_COMMANDS)) {
            event.addRequest(CAPABILITY_COMMANDS);
        }
        if (!already.contains(CAPABILITY_MEMBERSHIP)) {
            event.addRequest(CAPABILITY_MEMBERSHIP);
        }
        if (!already.contains(CAPABILITY_TAGS)) {
            event.addRequest(CAPABILITY_TAGS);
        }
    }

    @CommandFilter("CLEARCHAT")
    @Handler(priority = Integer.MAX_VALUE - 2)
    public void clearChat(ClientReceiveCommandEvent event) {
        Optional<MessageTag> reasonTag = event.getMessageTags().stream().filter(tag -> tag instanceof BanReason).findAny();
        if (!reasonTag.isPresent() || !reasonTag.get().getValue().isPresent()) {
            throw new KittehServerMessageException(event.getServerMessage(), "No ban reason present in ban");
        }
        String reason = reasonTag.get().getValue().get();
        Optional<Channel> channel = this.client.getChannel(event.getParameters().get(0));
        if (!channel.isPresent()) {
            throw new KittehServerMessageException(event.getServerMessage(), "Invalid channel name");
        }
        Optional<MessageTag> durationTag = event.getMessageTags().stream().filter(tag -> tag instanceof BanDuration).findAny();
        OptionalInt duration = durationTag
                .map(Stream::of)
                .orElseGet(Stream::empty)
                .mapToInt(tag -> ((BanDuration) tag).getDuration())
                .findFirst();
        this.client.getEventManager().callEvent(new ClearChatEvent(this.client, event.getOriginalMessages(), channel.get(), reason, duration));
    }

    @CommandFilter("GLOBALUSERSTATE")
    @Handler(priority = Integer.MAX_VALUE - 2)
    public void globalUserState(ClientReceiveCommandEvent event) {
        this.client.getEventManager().callEvent(new GlobalUserStateEvent(this.client, event.getOriginalMessages()));
    }

    @CommandFilter("ROOMSTATE")
    @Handler(priority = Integer.MAX_VALUE - 2)
    public void roomState(ClientReceiveCommandEvent event) {
        Optional<Channel> channel = this.client.getChannel(event.getParameters().get(0));
        if (!channel.isPresent()) {
            throw new KittehServerMessageException(event.getServerMessage(), "Invalid channel name");
        }
        this.client.getEventManager().callEvent(new RoomStateEvent(this.client, event.getOriginalMessages(), channel.get()));
    }

    @CommandFilter("USERNOTICE")
    @Handler(priority = Integer.MAX_VALUE - 2)
    public void userNotice(ClientReceiveCommandEvent event) {
        Optional<Channel> channel = this.client.getChannel(event.getParameters().get(0));
        if (!channel.isPresent()) {
            throw new KittehServerMessageException(event.getServerMessage(), "Invalid channel name");
        }
        String message = null;
        if (event.getParameters().size() > 1) {
            message = event.getParameters().get(1);
        }
        this.client.getEventManager().callEvent(new UserNoticeEvent(this.client, event.getOriginalMessages(), channel.get(), message));
    }

    @CommandFilter("USERSTATE")
    @Handler(priority = Integer.MAX_VALUE - 2)
    public void userState(ClientReceiveCommandEvent event) {
        Optional<Channel> channel = this.client.getChannel(event.getParameters().get(0));
        if (!channel.isPresent()) {
            throw new KittehServerMessageException(event.getServerMessage(), "Invalid channel name");
        }
        this.client.getEventManager().callEvent(new UserStateEvent(this.client, event.getOriginalMessages(), channel.get()));
    }
}