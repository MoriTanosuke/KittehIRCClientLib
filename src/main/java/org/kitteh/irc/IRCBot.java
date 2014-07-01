/*
 * * Copyright (C) 2013-2014 Matt Baxter http://kitteh.org
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
package org.kitteh.irc;

import org.kitteh.irc.elements.Actor;
import org.kitteh.irc.elements.Channel;
import org.kitteh.irc.event.ChannelCTCPEvent;
import org.kitteh.irc.event.ChannelMessageEvent;
import org.kitteh.irc.event.ChannelModeEvent;
import org.kitteh.irc.event.PrivateCTCPEvent;
import org.kitteh.irc.event.PrivateMessageEvent;
import org.kitteh.irc.util.LCSet;
import org.kitteh.irc.util.Sanity;
import org.kitteh.irc.util.StringUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Date;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IRCBot implements Bot {
    private class BotManager extends Thread {
        private BotManager() {
            this.setName("Kitteh IRCBot Main (" + IRCBot.this.getName() + ")");
            this.start();
        }

        @Override
        public void run() {
            IRCBot.this.run();
        }
    }

    private class BotProcessor extends Thread {
        private final Queue<String> queue = new ConcurrentLinkedQueue<>();

        private BotProcessor() {
            this.setName("Kitteh IRCBot Input Processor (" + IRCBot.this.getName() + ")");
            this.start();
        }

        @Override
        public void run() {
            while (!this.isInterrupted()) {
                if (this.queue.isEmpty()) {
                    synchronized (this.queue) {
                        try {
                            this.queue.wait();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
                try {
                    IRCBot.this.handleLine(this.queue.poll());
                } catch (final Throwable thrown) {
                    // NOOP
                }
            }
        }

        private void queue(String message) {
            synchronized (this.queue) {
                this.queue.add(message);
                this.queue.notify();
            }
        }
    }

    private enum MessageTarget {
        CHANNEL,
        PRIVATE,
        UNKNOWN
    }

    private final Config config;
    private final BotManager manager;
    private final BotProcessor processor;

    // TODO nick tracking needs major improvement
    private String nick;
    private String currentNick;

    private final Set<String> channels = new LCSet();

    private AuthType authType;
    private String auth;
    private String authReclaim;

    private IRCBotInput inputHandler;
    private IRCBotOutput outputHandler;

    private String shutdownReason;

    private boolean connected;
    private long lastCheck;

    private final EventManager eventManager = new EventManager();

    private Map<Character, Character> prefixes = new ConcurrentHashMap<Character, Character>() {
        {
            put('o', '@');
            put('v', '+');
        }
    };
    private static final Pattern PREFIX_PATTERN = Pattern.compile("PREFIX=\\(([a-zA-Z]+)\\)([^ ]+)");
    private Map<Character, ChannelModeType> modes = new ConcurrentHashMap<Character, ChannelModeType>() {
        {
            put('b', ChannelModeType.A_MASK);
            put('k', ChannelModeType.B_PARAMETER_ALWAYS);
            put('l', ChannelModeType.C_PARAMETER_ON_SET);
            put('i', ChannelModeType.D_PARAMETER_NEVER);
            put('m', ChannelModeType.D_PARAMETER_NEVER);
            put('n', ChannelModeType.D_PARAMETER_NEVER);
            put('p', ChannelModeType.D_PARAMETER_NEVER);
            put('s', ChannelModeType.D_PARAMETER_NEVER);
            put('t', ChannelModeType.D_PARAMETER_NEVER);
        }
    };
    private static final Pattern CHANMODES_PATTERN = Pattern.compile("CHANMODES=(([,A-Za-z]+)(,([,A-Za-z]+)){0,3})");

    IRCBot(Config config) {
        this.config = config;
        this.currentNick = this.nick = this.config.get(Config.NICK);
        this.manager = new BotManager();
        this.processor = new BotProcessor();
    }

    @Override
    public void addChannel(String... channels) {
        Sanity.nullCheck(channels, "Channels cannot be null");
        Sanity.truthiness(channels.length > 0, "Channels cannot be empty array");
        for (String channel : channels) {
            if (!Channel.isChannel(channel)) {
                continue;
            }
            this.channels.add(channel);
            if (this.connected) {
                this.sendRawLine("JOIN :" + channel, true);
            }
        }
    }

    @Override
    public EventManager getEventManager() {
        return this.eventManager;
    }

    @Override
    public String getIntendedNick() {
        return this.nick;
    }

    @Override
    public String getName() {
        return this.config.get(Config.BOT_NAME);
    }

    @Override
    public String getNick() {
        return this.currentNick;
    }

    @Override
    public void sendMessage(String target, String message) {
        Sanity.nullCheck(target, "Target cannot be null");
        Sanity.nullCheck(message, "Message cannot be null");
        Sanity.truthiness(target.indexOf(' ') == -1, "Target cannot have spaces");
        this.sendRawLine("PRIVMSG " + target + " :" + message);
    }

    @Override
    public void sendRawLine(String message) {
        this.sendRawLine(message, false);
    }

    @Override
    public void sendRawLine(String message, boolean priority) {
        Sanity.nullCheck(message, "Message cannot be null");
        this.outputHandler.queueMessage(message, priority);
    }

    @Override
    public void setAuth(AuthType type, String nick, String pass) {
        Sanity.nullCheck(type, "Auth type cannot be null");
        this.authType = type;
        switch (type) {
            case GAMESURGE:
                this.auth = "PRIVMSG AuthServ@services.gamesurge.net :auth " + nick + " " + pass;
                this.authReclaim = "";
                break;
            case NICKSERV:
            default:
                this.auth = "PRIVMSG NickServ :identify " + pass;
                this.authReclaim = "PRIVMSG NickServ :ghost " + nick + " " + pass;
        }
    }

    @Override
    public void setNick(String nick) {
        Sanity.nullCheck(nick, "Nick cannot be null");
        this.nick = nick.trim();
        this.sendNickChange(this.nick);
        this.currentNick = this.nick;
    }

    @Override
    public void shutdown(String reason) {
        if (reason == null) {
            reason = "";
        }
        this.shutdownReason = reason;
        this.manager.interrupt();
        this.processor.interrupt();
    }

    /**
     * Queue up a line for processing.
     *
     * @param line line to be processed
     */
    void processLine(String line) {
        this.processor.queue(line);
    }

    private void run() {
        try {
            this.connect();
        } catch (final IOException e) {
            e.printStackTrace();
            if ((this.inputHandler != null) && this.inputHandler.isAlive() && !this.inputHandler.isInterrupted()) {
                this.inputHandler.interrupt();
            }
            return;
        }
        while (!this.manager.isInterrupted()) {
            try {
                Thread.sleep(1000);
            } catch (final InterruptedException e) {
                break;
            }
            if ((System.currentTimeMillis() - this.lastCheck) > 5000) {
                this.lastCheck = System.currentTimeMillis();
                if (this.inputHandler.timeSinceInput() > 250000) {
                    this.outputHandler.shutdown("Ping timeout! Reconnecting..."); // TODO event
                    this.inputHandler.shutdown();
                    try {
                        Thread.sleep(10000);
                    } catch (final InterruptedException e) {
                        break;
                    }
                    try {
                        this.connect();
                    } catch (final IOException e) {
                        // System.out.println("Unable to reconnect!");
                        // TODO log
                    }
                }
            }
        }
        this.outputHandler.shutdown(this.shutdownReason);
        this.inputHandler.shutdown();
    }

    private void connect() throws IOException {
        this.connected = false;
        final Socket socket = new Socket();
        if (this.config.get(Config.BIND_ADDRESS) != null) {
            try {
                socket.bind(this.config.get(Config.BIND_ADDRESS));
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
        socket.connect(this.config.get(Config.SERVER_ADDRESS));
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        final BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        this.outputHandler = new IRCBotOutput(bufferedWriter, this.getName());
        this.outputHandler.start();
        if (this.config.get(Config.SERVER_PASSWORD) != null) {
            this.sendRawLine("PASS " + this.config.get(Config.SERVER_PASSWORD), true);
        }
        this.sendRawLine("USER " + this.config.get(Config.USER) + " 8 * :" + this.config.get(Config.REAL_NAME), true);
        this.sendNickChange(this.nick);
        String line;
        while ((line = bufferedReader.readLine()) != null) { // TODO hacky
            if (line.startsWith("PING ")) { // TODO duplicated here and in Input
                this.sendRawLine("PONG " + line.substring(5), true);
                continue;
            }
            try {
                this.handleLine(line);
            } catch (Throwable thrown) {
                // NOOP
            }
            final String[] split = line.split(" ");
            if (split.length > 3) {
                final String code = split[1];
                if (code.equals("004")) {
                    break;
                } else if (code.startsWith("5") || code.startsWith("4")) {
                    socket.close();
                    throw new RuntimeException("Could not log into the IRC server: " + line);
                }
            }
        }
        if (this.authReclaim != null && !this.currentNick.equals(this.nick) && this.authType.isNickOwned()) {
            this.sendRawLine(this.authReclaim, true);
            this.sendNickChange(this.nick);
        }
        if (this.auth != null) {
            this.sendRawLine(this.auth, true);
        }
        for (final String channel : this.channels) {
            this.sendRawLine("JOIN :" + channel, true);
        }
        this.outputHandler.readyForLowPriority();
        this.inputHandler = new IRCBotInput(socket, bufferedReader, this);
        this.inputHandler.start();
        this.connected = true;
    }

    private String handleColon(String string) {
        return string.startsWith(":") ? string.substring(1) : string;
    }

    private void handleLine(String line) throws Throwable {
        if ((line == null) || (line.length() == 0)) {
            return;
        }
        final String[] split = line.split(" ");
        if ((split.length <= 1) || !split[0].startsWith(":")) {
            return; // Invalid!
        }
        final Actor actor = Actor.getActor(split[0].substring(1));
        int numeric = -1;
        try {
            numeric = Integer.parseInt(split[1]);
        } catch (NumberFormatException ignored) {
        }
        if (numeric > -1) {
            switch (numeric) {
                case 1: // Welcome
                case 2: // Your host is...
                    break;
                // More stuff sent on startup
                case 3: // server created
                    break;
                case 4: // version / modes
                    break;
                case 5:
                    for (int i = 2; i < split.length; i++) {
                        Matcher prefixMatcher = PREFIX_PATTERN.matcher(split[i]);
                        if (prefixMatcher.find()) {
                            String modes = prefixMatcher.group(1);
                            String display = prefixMatcher.group(2);
                            if (modes.length() == display.length()) {
                                Map<Character, Character> map = new ConcurrentHashMap<>();
                                for (int x = 0; x < modes.length(); x++) {
                                    map.put(modes.charAt(x), display.charAt(x));
                                }
                                this.prefixes = map;
                            }
                            continue;
                        }
                        Matcher modeMatcher = CHANMODES_PATTERN.matcher(split[i]);
                        if (modeMatcher.find()) {
                            String[] modes = modeMatcher.group(1).split(",");
                            Map<Character, ChannelModeType> map = new ConcurrentHashMap<>();
                            for (int typeId = 0; typeId < modes.length; typeId++) {
                                for (char c : modes[typeId].toCharArray()) {
                                    ChannelModeType type = null;
                                    switch (typeId) {
                                        case 0:
                                            type = ChannelModeType.A_MASK;
                                            break;
                                        case 1:
                                            type = ChannelModeType.B_PARAMETER_ALWAYS;
                                            break;
                                        case 2:
                                            type = ChannelModeType.C_PARAMETER_ON_SET;
                                            break;
                                        case 3:
                                            type = ChannelModeType.D_PARAMETER_NEVER;
                                    }
                                    map.put(c, type);
                                }
                            }
                            this.modes = map;
                        }
                    }
                    break;
                case 250: // Highest connection count
                case 251: // There are X users
                case 252: // X IRC OPs
                case 253: // X unknown connections
                case 254: // X channels formed
                case 255: // X clients, X servers
                case 265: // Local users, max
                case 266: // global users, max
                case 372: // info, such as continued motd
                case 375: // motd start
                case 376: // motd end
                    break;
                // Channel info
                case 332: // Channel topic
                case 333: // Topic set by
                case 353: // Channel users list (/names). format is 353 nick = #channel :names
                case 366: // End of /names
                case 422: // MOTD missing
                    break;
                case 433: // TODO better nick management
                    if (!this.connected) {
                        this.currentNick = this.currentNick + '`';
                        this.sendNickChange(this.currentNick);
                    }
                    break;
            }
        } else {
            // CTCP
            MessageTarget messageTarget = this.getTypeByTarget(split[2]);
            if (split[1].equals("PRIVMSG") && (line.indexOf(":\u0001") > 0) && line.endsWith("\u0001")) { // TODO inaccurate
                final String ctcp = line.substring(line.indexOf(":\u0001") + 2, line.length() - 1);
                String reply = null;
                switch (messageTarget) {
                    case PRIVATE:
                        if (ctcp.equals("VERSION")) {
                            reply = "VERSION I am Kitteh!";
                        } else if (ctcp.equals("TIME")) {
                            reply = "TIME " + new Date().toString();
                        } else if (ctcp.equals("FINGER")) {
                            reply = "FINGER om nom nom tasty finger";
                        } else if (ctcp.startsWith("PING ")) {
                            reply = ctcp;
                        }
                        PrivateCTCPEvent event = new PrivateCTCPEvent(actor, ctcp, reply);
                        this.eventManager.callEvent(event);
                        reply = event.getReply();
                        break;
                    case CHANNEL:
                        this.eventManager.callEvent(new ChannelCTCPEvent(actor, (Channel) Actor.getActor(split[2]), ctcp));
                        break;
                }
                if (reply != null) {
                    this.sendRawLine("NOTICE " + actor.getName() + " :\u0001" + reply + "\u0001", false); // TODO is this correct?
                }
                return;
            }
            switch (split[1]) {
                case "NOTICE":
                    final String notice = this.handleColon(StringUtil.combineSplit(split, 3));
                    // TODO event
                    break;
                case "PRIVMSG":
                    final String message = this.handleColon(StringUtil.combineSplit(split, 3));
                    switch (messageTarget) {
                        case CHANNEL:
                            this.eventManager.callEvent(new ChannelMessageEvent(actor, (Channel) Actor.getActor(split[2]), message));
                            break;
                        case PRIVATE:
                            this.eventManager.callEvent(new PrivateMessageEvent(actor, message));
                            break;
                    }
                    break;
                case "MODE":
                    if (messageTarget == MessageTarget.CHANNEL) {
                        Channel channel = (Channel) Actor.getActor(split[2]);
                        String modechanges = split[3];
                        int currentArg = 4;
                        boolean add;
                        switch (modechanges.charAt(0)) {
                            case '+':
                                add = true;
                                break;
                            case '-':
                                add = false;
                                break;
                            default:
                                return;
                        }
                        for (int i = 1; i < modechanges.length() && currentArg < split.length; i++) {
                            char next = modechanges.charAt(i);
                            if (next == '+') {
                                add = true;
                            } else if (next == '-') {
                                add = false;
                            } else {
                                boolean hasArg;
                                if (this.prefixes.containsKey(next)) {
                                    hasArg = true;
                                } else {
                                    ChannelModeType type = this.modes.get(next);
                                    if (type == null) {
                                        // TODO WHINE LOUDLY
                                        return;
                                    }
                                    hasArg = (add && type.isParameterOnSetting()) || (!add && type.isParameterOnRemoval());
                                }
                                String arg = null;
                                if (hasArg) {
                                    arg = split[currentArg++];
                                }
                                this.eventManager.callEvent(new ChannelModeEvent(actor, channel, add, next, arg));
                            }
                        }
                    }
                    break;
                case "JOIN":
                case "PART":
                case "QUIT":
                    break;
                case "KICK":
                    // System.out.println(split[2] + ": " + StringUtil.getNick(actor) + " kicked " + split[3] + ": " + this.handleColon(StringUtil.combineSplit(split, 4))); TODO EVENT
                    break;
                case "NICK":
                    break;
                case "INVITE":
                    if (messageTarget == MessageTarget.PRIVATE && this.channels.contains(split[3])) {
                        this.sendRawLine("JOIN " + split[3], false);
                    }
                    break;
            }
        }
    }

    private MessageTarget getTypeByTarget(String target) {
        if (this.currentNick.equalsIgnoreCase(target)) {
            return MessageTarget.PRIVATE;
        }
        if (this.channels.contains(target)) {
            return MessageTarget.CHANNEL;
        }
        return MessageTarget.UNKNOWN;
    }

    private void sendNickChange(String newnick) {
        this.sendRawLine("NICK " + newnick, true);
        this.currentNick = newnick;
    }
}