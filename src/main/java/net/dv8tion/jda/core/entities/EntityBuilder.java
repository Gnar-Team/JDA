/*
 *     Copyright 2015-2017 Austin Keener & Michael Ritter & Florian Spieß
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.core.entities;

import gnu.trove.map.TLongObjectMap;
import net.dv8tion.jda.bot.entities.ApplicationInfo;
import net.dv8tion.jda.bot.entities.impl.ApplicationInfoImpl;
import net.dv8tion.jda.client.entities.*;
import net.dv8tion.jda.client.entities.impl.*;
import net.dv8tion.jda.core.*;
import net.dv8tion.jda.core.audit.ActionType;
import net.dv8tion.jda.core.audit.AuditLogChange;
import net.dv8tion.jda.core.audit.AuditLogEntry;
import net.dv8tion.jda.core.entities.MessageEmbed.*;
import net.dv8tion.jda.core.entities.impl.*;
import net.dv8tion.jda.core.exceptions.AccountTypeException;
import net.dv8tion.jda.core.handle.GuildMembersChunkHandler;
import net.dv8tion.jda.core.handle.ReadyHandler;
import net.dv8tion.jda.core.utils.MiscUtil;
import net.dv8tion.jda.core.utils.data.DataArray;
import net.dv8tion.jda.core.utils.data.DataObject;
import net.dv8tion.jda.core.utils.data.DataReadException;
import net.dv8tion.jda.core.utils.SimpleLog;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EntityBuilder
{
    public static final SimpleLog LOG = SimpleLog.getLog(EntityBuilder.class);
    public static final String MISSING_CHANNEL = "MISSING_CHANNEL";
    public static final String MISSING_USER = "MISSING_USER";

    private static final Pattern channelMentionPattern = Pattern.compile("<#(\\d+)>");

    protected final JDAImpl api;
    protected final TLongObjectMap<DataObject> cachedGuildJsons = MiscUtil.newLongMap();
    protected final TLongObjectMap<Consumer<Guild>> cachedGuildCallbacks = MiscUtil.newLongMap();

    public EntityBuilder(JDA api)
    {
        this.api = (JDAImpl) api;
    }

    public SelfUser createSelfUser(DataObject self)
    {
        SelfUserImpl selfUser = ((SelfUserImpl) api.getSelfUser());
        if (selfUser == null)
        {
            final long id = self.getLong("id");
            selfUser = new SelfUserImpl(id, api);
            api.setSelfUser(selfUser);
        }
        if (!api.getUserMap().containsKey(selfUser.getIdLong()))
        {
            api.getUserMap().put(selfUser.getIdLong(), selfUser);
        }
        return (SelfUser) selfUser
                .setVerified(self.getBoolean("verified"))
                .setMfaEnabled(self.getBoolean("mfa_enabled"))
                .setEmail(!self.isNull("email") ? self.getString("email") : null)
                .setName(self.getString("username"))
                .setDiscriminator(self.getString("discriminator"))
                .setAvatarId(self.isNull("avatar") ? null : self.getString("avatar"))
                .setBot(self.containsKey("bot") && self.getBoolean("bot"));
    }

    public Game createGame(String name, String url, Game.GameType type)
    {
        return new Game(name, url, type);
    }

    public void createGuildFirstPass(DataObject guild, Consumer<Guild> secondPassCallback)
    {
        final long id = guild.getLong("id");
        GuildImpl guildObj = ((GuildImpl) api.getGuildMap().get(id));
        if (guildObj == null)
        {
            guildObj = new GuildImpl(api, id);
            api.getGuildMap().put(id, guildObj);
        }
        if (guild.containsKey("unavailable") && guild.getBoolean("unavailable"))
        {
            guildObj.setAvailable(false);
            //This is used for when GuildCreateHandler receives a guild that is currently unavailable. During normal READY
            // loading for bots (which unavailable is always true) the secondPassCallback parameter will always
            // be null.
            if (secondPassCallback != null)
                secondPassCallback.accept(guildObj);
            api.getGuildLock().lock(id);
            return;
        }

        //If we make it to here, the Guild is available. This means 1 of 2 things:
        //Either:
        // 1) This is Guild provided during READY for a Client account
        // 2) This is a Guild received from GuildCreateHandler from a GUILD_CREATE event.
        //      This could be triggered by joining a guild or due to discord finally
        //      providing us with Guild information about a previously unavailable guild.
        //      Whether it was unavailable due to Bot READY unavailability or due to an
        //      outage within discord matters now.
        //
        // Either way, we now have enough information to fill in the general information about the Guild.
        // This does NOT necessarily mean that we have all information to complete the guild.
        // For Client accounts, we will also need to use op 12 (GUILD_SYNC) to get all presences of online users because
        // discord only provides Online users that we have an open PM channel with or are friends with for Client accounts.
        // On larger guilds we will still need to request all users using op 8 (GUILD_MEMBERS_CHUNK).
        //
        // The code below takes the information we -do- have and starts to fill in the Guild. It won't create anything
        // that might rely on Users that we don't have due to needing the GUILD_MEMBERS_CHUNK
        // This includes making VoiceStatus and PermissionOverrides

        guildObj.setAvailable(true)
                .setIconId(guild.isNull("icon") ? null : guild.getString("icon"))
                .setSplashId(guild.isNull("splash") ? null : guild.getString("splash"))
                .setRegion(Region.fromKey(guild.getString("region")))
                .setName(guild.getString("name"))
                .setAfkTimeout(Guild.Timeout.fromKey(guild.getInt("afk_timeout")))
                .setVerificationLevel(Guild.VerificationLevel.fromKey(guild.getInt("verification_level")))
                .setDefaultNotificationLevel(Guild.NotificationLevel.fromKey(guild.getInt("default_message_notifications")))
                .setRequiredMFALevel(Guild.MFALevel.fromKey(guild.getInt("mfa_level")))
                .setExplicitContentLevel(Guild.ExplicitContentLevel.fromKey(guild.getInt("explicit_content_filter")));

        DataArray roles = guild.getArray("roles");
        for (int i = 0; i < roles.length(); i++)
        {
            Role role = createRole(roles.getObject(i), guildObj.getIdLong());
            guildObj.getRolesMap().put(role.getIdLong(), role);
            if (role.getIdLong() == guildObj.getIdLong())
                guildObj.setPublicRole(role);
        }

        if (!guild.isNull("emojis"))
        {
            DataArray array = guild.getArray("emojis");
            TLongObjectMap<Emote> emoteMap = guildObj.getEmoteMap();
            for (int i = 0; i < array.length(); i++)
            {
                DataObject object = array.getObject(i);
                DataArray emoteRoles = object.getArray("roles");
                final long emoteId = object.getLong("id");

                EmoteImpl emoteObj = new EmoteImpl(emoteId, guildObj);
                Set<Role> roleSet = emoteObj.getRoleSet();

                for (int j = 0; j < emoteRoles.length(); j++)
                    roleSet.add(guildObj.getRoleById(emoteRoles.getLong(j)));
                emoteMap.put(emoteId, emoteObj
                        .setName(object.getString("name"))
                        .setManaged(object.getBoolean("managed")));
            }
        }

        if (guild.containsKey("members"))
        {
            DataArray members = guild.getArray("members");
            createGuildMemberPass(guildObj, members);
        }

        //This could be null for Client accounts. Will be fixed by GUILD_SYNC
        Member owner = guildObj.getMemberById(guild.getLong("owner_id"));
        if (owner != null)
            guildObj.setOwner(owner);

        if (guild.containsKey("presences"))
        {
            DataArray presences = guild.getArray("presences");
            for (int i = 0; i < presences.length(); i++)
            {
                DataObject presence = presences.getObject(i);
                final long userId = presence.getObject("user").getLong("id");
                MemberImpl member = (MemberImpl) guildObj.getMembersMap().get(userId);

                if (member == null)
                    LOG.debug("Received a ghost presence in GuildFirstPass! Guild: " + guildObj + " UserId: " + userId);
                else
                    createPresence(member, presence);
            }
        }

        if (guild.containsKey("channels"))
        {
            DataArray channels = guild.getArray("channels");

            for (int i = 0; i < channels.length(); i++)
            {
                DataObject channel = channels.getObject(i);
                ChannelType type = ChannelType.fromId(channel.getInt("type"));
                switch (type)
                {
                    case TEXT:
                        createTextChannel(channel, guildObj.getIdLong(), false);
                        break;
                    case VOICE:
                        createVoiceChannel(channel, guildObj.getIdLong(), false);
                        break;
                    case CATEGORY:
                        createCategory(channel, guildObj.getIdLong(), false);
                        break;
                    default:
                        LOG.fatal("Received a channel for a guild that isn't a text, voice or category channel. JSON: " + channel);
                }
            }
        }

        if (!guild.isNull("system_channel_id"))
            guildObj.setSystemChannel(guildObj.getTextChannelsMap().get(guild.getLong("system_channel_id")));

        if (!guild.isNull("afk_channel_id"))
            guildObj.setAfkChannel(guildObj.getVoiceChannelsMap().get(guild.getLong("afk_channel_id")));

        //If the members that we were provided with (and loaded above) were not all of the
        //  the members in this guild, then we need to request more users from Discord using
        //  op 9 (GUILD_MEMBERS_CHUNK). To do so, we will cache the guild's JSON so we can properly
        //  load stuff that relies on Users like Channels, PermissionOverrides and VoiceStatuses
        //  after we have the rest of the users. We will request the GUILD_MEMBERS_CHUNK information
        //  which will be sent from discord over the main Websocket and will be handled by
        //  GuildMemberChunkHandler. After the handler has received all users as determined by the
        //  value set using `setExpectedGuildMembers`, it will do one of the following:
        //    1) If this is a Bot account, immediately call EntityBuilder#createGuildSecondPass, thus finishing
        //        the Guild object creation process.
        //    2) If this is a Client account, it will request op 12 (GUILD_SYNC) to make sure we have all information
        //        about online users as GUILD_MEMBERS_CHUNK does not include presence information, and when loading the
        //        members from GUILD_MEMBERS_CHUNK, we assume they are offline. GUILD_SYNC makes sure that we mark them
        //        properly. After GUILD_SYNC is received by GuildSyncHandler, it will call EntityBuilder#createGuildSecondPass
        //
        //If we actually -did- get all of the users needed, then we don't need to Chunk. Furthermore,
        // we don't need to use GUILD_SYNC because we always get presences with users thus we have all information
        // needed to guild the Guild. We will skip
        if (guild.getArray("members").length() != guild.getInt("member_count"))
        {
            cachedGuildJsons.put(id, guild);
            cachedGuildCallbacks.put(id, secondPassCallback);

            GuildMembersChunkHandler handler = api.getClient().getHandler("GUILD_MEMBERS_CHUNK");
            handler.setExpectedGuildMembers(id, guild.getInt("member_count"));

            //If we are already past READY / RESUME, then chunk at runtime. Otherwise, pass back to the ReadyHandler
            // and let it send a burst chunk request.
            if (api.getClient().isReady())
            {
                if (api.getAccountType() == AccountType.CLIENT)
                {
                    DataObject obj = new DataObject()
                            .put("op", WebSocketCode.GUILD_SYNC)
                            .put("guild_id", guildObj.getIdLong());
                    api.getClient().chunkOrSyncRequest(obj);
                }
                DataObject obj = new DataObject()
                        .put("op", WebSocketCode.MEMBER_CHUNK_REQUEST)
                        .put("d", new DataObject()
                            .put("guild_id", id)
                            .put("query","")
                            .put("limit", 0)
                        );
                api.getClient().chunkOrSyncRequest(obj);
            }
            else
            {
                ReadyHandler readyHandler = api.getClient().getHandler("READY");
                readyHandler.acknowledgeGuild(guildObj, true, true, api.getAccountType() == AccountType.CLIENT);
            }

            api.getGuildLock().lock(id);
            return;
        }

        //As detailed in the comment above, if we've made it this far then we have all member information needed to
        // create the Guild. Thus, we fill in the remaining information, unlock the guild, and provide the guild
        // to the callback
        //This should only occur on small user count guilds.

        DataArray channels = guild.getArray("channels");
        createGuildChannelPass(guildObj, channels); //Actually creates PermissionOverrides

        DataArray voiceStates = guild.getArray("voice_states");
        createGuildVoiceStatePass(guildObj, voiceStates);

        api.getGuildLock().unlock(guildObj.getIdLong());
        if (secondPassCallback != null)
            secondPassCallback.accept(guildObj);
    }

    public void createGuildSecondPass(long guildId, List<DataArray> memberChunks)
    {
        DataObject guildJson = cachedGuildJsons.remove(guildId);
        Consumer<Guild> secondPassCallback = cachedGuildCallbacks.remove(guildId);
        GuildImpl guildObj = (GuildImpl) api.getGuildMap().get(guildId);

        if (guildObj == null)
            throw new IllegalStateException("Attempted to perform a second pass on an unknown Guild. Guild not in JDA " +
                    "mapping. GuildId: " + guildId);
        if (guildJson == null)
            throw new IllegalStateException("Attempted to perform a second pass on an unknown Guild. No cached Guild " +
                    "for second pass. GuildId: " + guildId);
        if (secondPassCallback == null)
            throw new IllegalArgumentException("No callback provided for the second pass on the Guild!");

        for (DataArray chunk : memberChunks)
            createGuildMemberPass(guildObj, chunk);

        Member owner = guildObj.getMemberById(guildJson.getLong("owner_id"));
        if (owner != null)
            guildObj.setOwner(owner);

        if (guildObj.getOwner() == null)
            LOG.fatal("Never set the Owner of the Guild: " + guildObj.getId() + " because we don't have the owner User object! How?!");

        DataArray channels = guildJson.getArray("channels");
        createGuildChannelPass(guildObj, channels);

        DataArray voiceStates = guildJson.getArray("voice_states");
        createGuildVoiceStatePass(guildObj, voiceStates);

        secondPassCallback.accept(guildObj);
        api.getGuildLock().unlock(guildId);
    }

    public void handleGuildSync(GuildImpl guild, DataArray members, DataArray presences)
    {
        for (int i = 0; i < members.length(); i++)
        {
            DataObject memberJson = members.getObject(i);
            createMember(guild, memberJson);
        }

        for (int i = 0; i < presences.length(); i++)
        {
            DataObject presenceJson = presences.getObject(i);
            final long userId = presenceJson.getObject("user").getLong("id");

            MemberImpl member = (MemberImpl) guild.getMembersMap().get(userId);
            if (member == null)
                LOG.fatal("Received a Presence for a non-existent Member when dealing with GuildSync!");
            else
                this.createPresence(member, presenceJson);
        }
    }

    private void createGuildMemberPass(GuildImpl guildObj, DataArray members)
    {
        for (int i = 0; i < members.length(); i++)
        {
            DataObject memberJson = members.getObject(i);
            createMember(guildObj, memberJson);
        }
    }

    private void createGuildChannelPass(GuildImpl guildObj, DataArray channels)
    {
        for (int i = 0; i < channels.length(); i++)
        {
            DataObject channel = channels.getObject(i);
            ChannelType type = ChannelType.fromId(channel.getInt("type"));
            Channel channelObj = null;
            switch (type)
            {
                case TEXT:
                    channelObj = api.getTextChannelById(channel.getLong("id"));
                    break;
                case VOICE:
                    channelObj = api.getVoiceChannelById(channel.getLong("id"));
                    break;
                case CATEGORY:
                    channelObj = api.getCategoryMap().get(channel.getLong("id"));
                    break;
                default:
                    LOG.fatal("Received a channel for a guild that isn't a text, voice or category channel (ChannelPass). JSON: " + channel);
            }

            if (channelObj != null)
            {
                DataArray permissionOverwrites = channel.getArray("permission_overwrites");
                createOverridesPass((AbstractChannelImpl<?>) channelObj, permissionOverwrites);
            }
            else
            {
                LOG.fatal("Got permission_override for unknown channel with id: " + channel.getString("id"));
            }
        }
    }

    public void createGuildVoiceStatePass(GuildImpl guildObj, DataArray voiceStates)
    {
        for (int i = 0; i < voiceStates.length(); i++)
        {
            DataObject voiceStateJson = voiceStates.getObject(i);
            final long userId = voiceStateJson.getLong("user_id");
            Member member = guildObj.getMembersMap().get(userId);
            if (member == null)
            {
                LOG.fatal("Received a VoiceState for a unknown Member! GuildId: "
                        + guildObj.getId() + " MemberId: " + voiceStateJson.getString("user_id"));
                continue;
            }

            final long channelId = voiceStateJson.getLong("channel_id");
            VoiceChannelImpl voiceChannel =
                    (VoiceChannelImpl) guildObj.getVoiceChannelsMap().get(channelId);
            if (voiceChannel != null)
                voiceChannel.getConnectedMembersMap().put(member.getUser().getIdLong(), member);
            else
                LOG.fatal("Received a GuildVoiceState with a channel ID for a non-existent channel! " +
                    "ChannelId: " + channelId + " GuildId: " + guildObj.getId() + " UserId:" + userId);

            // VoiceState is considered volatile so we don't expect anything to actually exist
            GuildVoiceStateImpl voiceState = (GuildVoiceStateImpl) member.getVoiceState();
            voiceState.setSelfMuted(!voiceStateJson.isNull("self_mute") && voiceStateJson.getBoolean("self_mute"))
                      .setSelfDeafened(!voiceStateJson.isNull("self_deaf") && voiceStateJson.getBoolean("self_deaf"))
                      .setGuildMuted(!voiceStateJson.isNull("mute") && voiceStateJson.getBoolean("mute"))
                      .setGuildDeafened(!voiceStateJson.isNull("deaf") && voiceStateJson.getBoolean("deaf"))
                      .setSuppressed(!voiceStateJson.isNull("suppress") && voiceStateJson.getBoolean("suppress"))
                      .setSessionId(voiceStateJson.isNull("session_id") ? "" : voiceStateJson.getString("session_id"))
                      .setConnectedChannel(voiceChannel);
        }
    }

    public User createFakeUser(DataObject user, boolean modifyCache) { return createUser(user, true, modifyCache); }
    public User createUser(DataObject user)     { return createUser(user, false, true); }
    private User createUser(DataObject user, boolean fake, boolean modifyCache)
    {
        final long id = user.getLong("id");
        UserImpl userObj;

        userObj = (UserImpl) api.getUserMap().get(id);
        if (userObj == null)
        {
            userObj = (UserImpl) api.getFakeUserMap().get(id);
            if (userObj != null)
            {
                if (!fake && modifyCache)
                {
                    api.getFakeUserMap().remove(id);
                    userObj.setFake(false);
                    api.getUserMap().put(userObj.getIdLong(), userObj);
                    if (userObj.hasPrivateChannel())
                    {
                        PrivateChannelImpl priv = (PrivateChannelImpl) userObj.getPrivateChannel();
                        priv.setFake(false);
                        api.getFakePrivateChannelMap().remove(priv.getIdLong());
                        api.getPrivateChannelMap().put(priv.getIdLong(), priv);
                    }
                }
            }
            else
            {
                userObj = new UserImpl(id, api).setFake(fake);
                if (modifyCache)
                {
                    if (fake)
                        api.getFakeUserMap().put(id, userObj);
                    else
                        api.getUserMap().put(id, userObj);
                }
            }
        }

        return userObj
                .setName(user.getString("username"))
                .setDiscriminator(user.get("discriminator").toString())
                .setAvatarId(user.isNull("avatar") ? null : user.getString("avatar"))
                .setBot(user.containsKey("bot") && user.getBoolean("bot"));
    }

    public Member createMember(GuildImpl guild, DataObject memberJson)
    {
        User user = createUser(memberJson.getObject("user"));
        MemberImpl member = (MemberImpl) guild.getMember(user);
        if (member == null)
        {
            member = new MemberImpl(guild, user);
            guild.getMembersMap().put(user.getIdLong(), member);
        }

        ((GuildVoiceStateImpl) member.getVoiceState())
            .setGuildMuted(memberJson.getBoolean("mute"))
            .setGuildDeafened(memberJson.getBoolean("deaf"));

        member.setJoinDate(OffsetDateTime.parse(memberJson.getString("joined_at")))
              .setNickname(memberJson.isNull("nick") ? null : memberJson.getString("nick"));

        DataArray rolesJson = memberJson.getArray("roles");
        for (int k = 0; k < rolesJson.length(); k++)
        {
            final long roleId = rolesJson.getLong(k);
            Role r = guild.getRolesMap().get(roleId);
            if (r == null)
            {
                LOG.debug("Received a Member with an unknown Role. MemberId: "
                        + member.getUser().getId() + " GuildId: " + guild.getId() + " roleId: " + roleId);
            }
            else
            {
                member.getRoleSet().add(r);
            }
        }

        return member;
    }

    //Effectively the same as createFriendPresence
    public void createPresence(Object memberOrFriend, DataObject presenceJson)
    {
        if (memberOrFriend == null)
            throw new NullPointerException("Provided memberOrFriend was null!");

        DataObject gameJson = presenceJson.isNull("game") ? null : presenceJson.getDataObject("game");
        OnlineStatus onlineStatus = OnlineStatus.fromKey(presenceJson.getString("status"));
        Game game = null;

        if (gameJson != null && !gameJson.isNull("name"))
        {
            String gameName = gameJson.get("name").toString();
            String url = gameJson.isNull("url") ? null : gameJson.get("url").toString();

            Game.GameType gameType;
            try
            {
                gameType = gameJson.isNull("type")
                           ? Game.GameType.DEFAULT
                           : Game.GameType.fromKey(Integer.parseInt(gameJson.get("type").toString()));
            }
            catch (NumberFormatException e)
            {
                gameType = Game.GameType.DEFAULT;
            }

            game = createGame(gameName, url, gameType);
        }
        if (memberOrFriend instanceof Member)
        {
            MemberImpl member = (MemberImpl) memberOrFriend;
            member.setOnlineStatus(onlineStatus);
            member.setGame(game);
        }
        else if (memberOrFriend instanceof Friend)
        {
            FriendImpl friend = (FriendImpl) memberOrFriend;
            friend.setOnlineStatus(onlineStatus);
            friend.setGame(game);

            OffsetDateTime lastModified = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(presenceJson.getLong("last_modified")),
                    TimeZone.getTimeZone("GMT").toZoneId());

            friend.setOnlineStatusModifiedTime(lastModified);
        }
        else
            throw new IllegalArgumentException("An object was provided to EntityBuilder#createPresence that wasn't a Member or Friend. JSON: " + presenceJson);
    }

    public Category createCategory(DataObject json, long guildId)
    {
        return createCategory(json, guildId, true);
    }

    public Category createCategory(DataObject json, long guildId, boolean guildIsLoaded)
    {
        final long id = json.getLong("id");
        CategoryImpl channel = (CategoryImpl) api.getCategoryMap().get(id);
        if (channel == null)
        {
            GuildImpl guild = ((GuildImpl) api.getGuildMap().get(guildId));
            channel = new CategoryImpl(id, guild);
            guild.getCategoriesMap().put(id, channel);
            api.getCategoryMap().put(id, channel);
        }

        if (!json.isNull("permission_overwrites") && guildIsLoaded)
        {
            DataArray overrides = json.getArray("permission_overwrites");
            createOverridesPass(channel, overrides);
        }

        return channel
                .setName(json.getString("name"))
                .setRawPosition(json.getInt("position"));
    }

    public TextChannel createTextChannel(DataObject json, long guildId)
    {
        return createTextChannel(json, guildId, true);

    }

    public TextChannel createTextChannel(DataObject json, long guildId, boolean guildIsLoaded)
    {
        final long id = json.getLong("id");
        TextChannelImpl channel = (TextChannelImpl) api.getTextChannelMap().get(id);
        if (channel == null)
        {
            GuildImpl guild = ((GuildImpl) api.getGuildMap().get(guildId));
            channel = new TextChannelImpl(id, guild);
            guild.getTextChannelsMap().put(id, channel);
            api.getTextChannelMap().put(id, channel);
        }

        if (!json.isNull("permission_overwrites") && guildIsLoaded)
        {
<<<<<<< HEAD
            DataArray overrides = json.getArray("permission_overwrites");
            for (int i = 0; i < overrides.length(); i++)
            {
                createPermissionOverride(overrides.getObject(i), channel);
            }
=======
            DataArray overrides = json.getArray("permission_overwrites");
            createOverridesPass(channel, overrides);
>>>>>>> master
        }

        return channel
                .setParent(json.isNull("parent_id") ? 0 : json.getLong("parent_id"))
                .setLastMessageId(json.isNull("last_message_id") ? 0 : json.getLong("last_message_id"))
                .setName(json.getString("name"))
                .setTopic(json.isNull("topic") ? "" : json.getString("topic"))
                .setRawPosition(json.getInt("position"))
                .setNSFW(!json.isNull("nsfw") && json.getBoolean("nsfw"));
    }

    public VoiceChannel createVoiceChannel(DataObject json, long guildId)
    {
        return createVoiceChannel(json, guildId, true);
    }

    public VoiceChannel createVoiceChannel(DataObject json, long guildId, boolean guildIsLoaded)
    {
        final long id = json.getLong("id");
        VoiceChannelImpl channel = ((VoiceChannelImpl) api.getVoiceChannelMap().get(id));
        if (channel == null)
        {
            GuildImpl guild = (GuildImpl) api.getGuildMap().get(guildId);
            channel = new VoiceChannelImpl(id, guild);
            guild.getVoiceChannelsMap().put(id, channel);
            api.getVoiceChannelMap().put(id, channel);
        }

        if (!json.isNull("permission_overwrites") && guildIsLoaded)
        {
<<<<<<< HEAD
            DataArray overrides = json.getArray("permission_overwrites");
            for (int i = 0; i < overrides.length(); i++)
            {
                createPermissionOverride(overrides.getObject(i), channel);
            }
=======
            DataArray overrides = json.getArray("permission_overwrites");
            createOverridesPass(channel, overrides);
>>>>>>> master
        }

        return channel
                .setParent(json.isNull("parent_id") ? 0 : json.getLong("parent_id"))
                .setName(json.getString("name"))
                .setRawPosition(json.getInt("position"))
                .setUserLimit(json.getInt("user_limit"))
                .setBitrate(json.getInt("bitrate"));
    }

    public PrivateChannel createPrivateChannel(DataObject privatechat)
    {
<<<<<<< HEAD
        DataObject recipient = privatechat.containsKey("recipients") ?
            privatechat.getArray("recipients").getObject(0) :
            privatechat.getObject("recipient");
=======
        DataObject recipient = privatechat.has("recipients") ?
            privatechat.getArray("recipients").getDataObject(0) :
            privatechat.getDataObject("recipient");
>>>>>>> master
        final long userId = recipient.getLong("id");
        UserImpl user = ((UserImpl) api.getUserMap().get(userId));
        if (user == null)
        {   //The API can give us private channels connected to Users that we can no longer communicate with.
            // As such, make a fake user and fake private channel.
            user = (UserImpl) createFakeUser(recipient, true);
        }

        final long channelId = privatechat.getLong("id");
        PrivateChannelImpl priv = new PrivateChannelImpl(channelId, user)
                .setLastMessageId(privatechat.isNull("last_message_id") ? -1 : privatechat.getLong("last_message_id"));
        user.setPrivateChannel(priv);

        if (user.isFake())
        {
            priv.setFake(true);
            api.getFakePrivateChannelMap().put(channelId, priv);
        }
        else
            api.getPrivateChannelMap().put(channelId, priv);
        return priv;
    }

<<<<<<< HEAD
    public Role createRole(DataObject roleJson, long guildId)
=======
    public void createOverridesPass(AbstractChannelImpl<?> channel, DataArray overrides)
    {
        for (int i = 0; i < overrides.length(); i++)
        {
            try
            {
                createPermissionOverride(overrides.getDataObject(i), channel);
            }
            catch (NoSuchElementException e)
            {
                //Caused by Discord not properly clearing PermissionOverrides when a Member leaves a Guild.
                LOG.debug(e.getMessage() + ". Ignoring PermissionOverride.");
            }
            catch (IllegalArgumentException e)
            {
                //Missing handling for a type
                LOG.warn(e.getMessage() + ". Ignoring PermissionOverride.");
            }
        }
    }

    public Role createRole(DataObject roleJson, long guildId)
>>>>>>> master
    {
        final long id = roleJson.getLong("id");
        GuildImpl guild = ((GuildImpl) api.getGuildMap().get(guildId));
        RoleImpl role = ((RoleImpl) guild.getRolesMap().get(id));
        if (role == null)
        {
            role = new RoleImpl(id, guild);
            guild.getRolesMap().put(id, role);
        }
        return role.setName(roleJson.getString("name"))
                .setRawPosition(roleJson.getInt("position"))
                .setRawPermissions(roleJson.getLong("permissions"))
                .setManaged(roleJson.getBoolean("managed"))
                .setHoisted(roleJson.getBoolean("hoist"))
                .setColor(roleJson.getInt("color") != 0 ? new Color(roleJson.getInt("color")) : null)
                .setMentionable(roleJson.containsKey("mentionable") && roleJson.getBoolean("mentionable"));
    }

    public Message createMessage(DataObject DataObject) { return createMessage(DataObject, false); }
    public Message createMessage(DataObject DataObject, boolean exceptionOnMissingUser)
    {
        final long channelId = DataObject.getLong("channel_id");

        MessageChannel chan = api.getTextChannelById(channelId);
        if (chan == null)
            chan = api.getPrivateChannelById(channelId);
        if (chan == null)
            chan = api.getFakePrivateChannelMap().get(channelId);
        if (chan == null && api.getAccountType() == AccountType.CLIENT)
            chan = api.asClient().getGroupById(channelId);
        if (chan == null)
            throw new IllegalArgumentException(MISSING_CHANNEL);

        return createMessage(DataObject, chan, exceptionOnMissingUser);
    }
    public Message createMessage(DataObject DataObject, MessageChannel chan, boolean exceptionOnMissingUser)
    {
        final long id = DataObject.getLong("id");
        String content = !DataObject.isNull("content") ? DataObject.getString("content") : "";

        DataObject author = DataObject.getObject("author");
        final long authorId = author.getLong("id");
        boolean fromWebhook = DataObject.containsKey("webhook_id");

        MessageImpl message = new MessageImpl(id, chan, fromWebhook)
                .setContent(content)
                .setTime(!DataObject.isNull("timestamp") ? OffsetDateTime.parse(DataObject.getString("timestamp")) : OffsetDateTime.now())
                .setMentionsEveryone(!DataObject.isNull("mention_everyone") && DataObject.getBoolean("mention_everyone"))
                .setTTS(!DataObject.isNull("tts") && DataObject.getBoolean("tts"))
                .setPinned(!DataObject.isNull("pinned") && DataObject.getBoolean("pinned"));
        if (chan instanceof PrivateChannel)
        {
            if (authorId == api.getSelfUser().getIdLong())
                message.setAuthor(api.getSelfUser());
            else
                message.setAuthor(((PrivateChannel) chan).getUser());
        }
        else if (chan instanceof Group)
        {
            UserImpl user = (UserImpl) api.getUserMap().get(authorId);
            if (user == null)
                user = (UserImpl) api.getFakeUserMap().get(authorId);
            if (user == null && fromWebhook)
                user = (UserImpl) createFakeUser(author, false);
            if (user == null)
            {
                if (exceptionOnMissingUser)
                    throw new IllegalArgumentException(MISSING_USER);   //Specifically for MESSAGE_CREATE
                else
                    user = (UserImpl) createFakeUser(author, false);  //Any message creation that isn't MESSAGE_CREATE
            }
            message.setAuthor(user);

            //If the message was sent by a cached fake user, lets update it.
            if (user.isFake() && !fromWebhook)
            {
                user.setName(author.getString("username"))
                        .setDiscriminator(author.get("discriminator").toString())
                        .setAvatarId(author.isNull("avatar") ? null : author.getString("avatar"))
                        .setBot(author.containsKey("bot") && author.getBoolean("bot"));
            }
        }
        else
        {
            GuildImpl guild = (GuildImpl) ((TextChannel) chan).getGuild();
            Member member = guild.getMembersMap().get(authorId);
            User user = member != null ? member.getUser() : null;
            if (user != null)
                message.setAuthor(user);
            else if (fromWebhook || !exceptionOnMissingUser)
                message.setAuthor(createFakeUser(author, false));
            else
                throw new IllegalArgumentException(MISSING_USER);
        }

        List<Message.Attachment> attachments = new LinkedList<>();
        if (!DataObject.isNull("attachments"))
        {
            DataArray jsonAttachments = DataObject.getArray("attachments");
            for (int i = 0; i < jsonAttachments.length(); i++)
            {
                DataObject jsonAttachment = jsonAttachments.getObject(i);
                attachments.add(new Message.Attachment(
                        jsonAttachment.getLong("id"),
                        jsonAttachment.getString("url"),
                        jsonAttachment.getString("proxy_url"),
                        jsonAttachment.getString("filename"),
                        jsonAttachment.getInt("size"),
                        jsonAttachment.containsKey("height") ? jsonAttachment.getInt("height") : 0,
                        jsonAttachment.containsKey("width") ? jsonAttachment.getInt("width") : 0,
                        api
                ));
            }
        }
        message.setAttachments(attachments);

        List<MessageEmbed> embeds = new LinkedList<>();
        DataArray jsonEmbeds = DataObject.getArray("embeds");
        for (int i = 0; i < jsonEmbeds.length(); i++)
        {
            embeds.add(createMessageEmbed(jsonEmbeds.getObject(i)));
        }
        message.setEmbeds(embeds);

        if (!DataObject.isNull("edited_timestamp"))
            message.setEditedTime(OffsetDateTime.parse(DataObject.getString("edited_timestamp")));

        if (DataObject.containsKey("reactions"))
        {
            DataArray reactions = DataObject.getArray("reactions");
            List<MessageReaction> list = new LinkedList<>();
            for (int i = 0; i < reactions.length(); i++)
            {
                DataObject obj = reactions.getObject(i);
                DataObject emoji = obj.getObject("emoji");

                final Long emojiId = emoji.isNull("id") ? null : emoji.getLong("id");
                String emojiName = emoji.getString("name");

                boolean self = obj.containsKey("self") && obj.getBoolean("self");
                int count = obj.getInt("count");
                Emote emote = null;
                if (emojiId != null)
                {
                    emote = api.getEmoteById(emojiId);
                    if (emote == null)
                        emote = new EmoteImpl(emojiId, api).setName(emojiName);
                }
                MessageReaction.ReactionEmote reactionEmote;
                if (emote == null)
                    reactionEmote = new MessageReaction.ReactionEmote(emojiName, null, api);
                else
                    reactionEmote = new MessageReaction.ReactionEmote(emote);
                list.add(new MessageReaction(chan, reactionEmote, message.getIdLong(), self, count));
            }
            message.setReactions(list);
        }

        if (message.isFromType(ChannelType.TEXT))
        {
            TextChannel textChannel = message.getTextChannel();
            TreeMap<Integer, User> mentionedUsers = new TreeMap<>();
            if (!DataObject.isNull("mentions"))
            {
                DataArray mentions = DataObject.getArray("mentions");
                for (int i = 0; i < mentions.length(); i++)
                {
                    DataObject mention = mentions.getObject(i);
                    User u = api.getUserById(mention.getLong("id"));
                    if (u != null)
                    {
                        //We do this to properly order the mentions. The array given by discord is out of order sometimes.

                        long mentionId = mention.getLong("id");
                        int index = content.indexOf("<@" + mentionId + ">");
                        if (index < 0)
                            index = content.indexOf("<@!" + mentionId + ">");
                        mentionedUsers.put(index, u);
                    }
                }
            }
            message.setMentionedUsers(new LinkedList<User>(mentionedUsers.values()));

            TreeMap<Integer, Role> mentionedRoles = new TreeMap<>();
            if (!DataObject.isNull("mention_roles"))
            {
                DataArray roleMentions = DataObject.getArray("mention_roles");
                for (int i = 0; i < roleMentions.length(); i++)
                {
                    long roleId = roleMentions.getLong(i);
                    Role r = textChannel.getGuild().getRoleById(roleId);
                    if (r != null)
                    {
                        int index = content.indexOf("<@&" + roleId + ">");
                        mentionedRoles.put(index, r);
                    }
                }
            }
            message.setMentionedRoles(new LinkedList<Role>(mentionedRoles.values()));

            List<TextChannel> mentionedChannels = new LinkedList<>();
            TLongObjectMap<TextChannel> chanMap = ((GuildImpl) textChannel.getGuild()).getTextChannelsMap();
            Matcher matcher = channelMentionPattern.matcher(content);
            while (matcher.find())
            {
                TextChannel channel = chanMap.get(Long.parseLong(matcher.group(1)));
                if(channel != null && !mentionedChannels.contains(channel))
                {
                    mentionedChannels.add(channel);
                }
            }
            message.setMentionedChannels(mentionedChannels);
        }
        return message;
    }

    public MessageEmbed createMessageEmbed(DataObject messageEmbed)
    {
        if (messageEmbed.isNull("type"))
            throw new DataReadException("Encountered embed object with missing/null type field for Json: " + messageEmbed);
        EmbedType type = EmbedType.fromKey(messageEmbed.getString("type"));
       /* if (type == EmbedType.UNKNOWN)
            throw new JSONException("Discord provided us an unknown embed type.  Json: " + messageEmbed);*/
        MessageEmbedImpl embed = new MessageEmbedImpl()
                .setType(type)
                .setUrl(messageEmbed.isNull("url") ? null : messageEmbed.getString("url"))
                .setTitle(messageEmbed.isNull("title") ? null : messageEmbed.getString("title"))
                .setDescription(messageEmbed.isNull("description") ? null : messageEmbed.getString("description"))
                .setColor(messageEmbed.isNull("color") || messageEmbed.getInt("color") == 0 ? null : new Color(messageEmbed.getInt("color")))
                .setTimestamp(messageEmbed.isNull("timestamp") ? null : OffsetDateTime.parse(messageEmbed.getString("timestamp")));

        if (messageEmbed.containsKey("thumbnail"))
        {
            DataObject thumbnailJson = messageEmbed.getObject("thumbnail");
            embed.setThumbnail(new Thumbnail(
                    thumbnailJson.getString("url"),
                    thumbnailJson.getString("proxy_url"),
                    thumbnailJson.getInt("width"),
                    thumbnailJson.getInt("height")));
        }
        else embed.setThumbnail(null);

        if (messageEmbed.containsKey("provider"))
        {
            DataObject providerJson = messageEmbed.getObject("provider");
            embed.setSiteProvider(new Provider(
                    providerJson.isNull("name") ? null : providerJson.getString("name"),
                    providerJson.isNull("url") ? null : providerJson.getString("url")));
        }
        else embed.setSiteProvider(null);

        if (messageEmbed.containsKey("author"))
        {
            DataObject authorJson = messageEmbed.getObject("author");
            embed.setAuthor(new AuthorInfo(
                    authorJson.isNull("name") ? null : authorJson.getString("name"),
                    authorJson.isNull("url") ? null : authorJson.getString("url"),
                    authorJson.isNull("icon_url") ? null : authorJson.getString("icon_url"),
                    authorJson.isNull("proxy_icon_url") ? null : authorJson.getString("proxy_icon_url")));
        }
        else embed.setAuthor(null);

        if (messageEmbed.containsKey("image"))
        {
            DataObject imageJson = messageEmbed.getObject("image");
            embed.setImage(new ImageInfo(
                    imageJson.isNull("url") ? null : imageJson.getString("url"),
                    imageJson.isNull("proxy_url") ? null : imageJson.getString("proxy_url"),
                    imageJson.isNull("width") ? -1 : imageJson.getInt("width"),
                    imageJson.isNull("height") ? -1 : imageJson.getInt("height")));
        }
        else embed.setImage(null);
        
        if (messageEmbed.containsKey("footer"))
        {
            DataObject footerJson = messageEmbed.getObject("footer");
            embed.setFooter(new Footer(
                    footerJson.isNull("text") ? null : footerJson.getString("text"),
                    footerJson.isNull("icon_url") ? null : footerJson.getString("icon_url"),
                    footerJson.isNull("proxy_icon_url") ? null : footerJson.getString("proxy_icon_url")));
        }
        else embed.setFooter(null);
        
        if (messageEmbed.containsKey("fields"))
        {
            DataArray fieldsJson = messageEmbed.getArray("fields");
            List<Field> fields = new LinkedList<>();
            for(int index=0; index<fieldsJson.length(); index++)
            {
                DataObject fieldJson = fieldsJson.getObject(index);
                fields.add(new Field(
                        fieldJson.isNull("name") ? null : fieldJson.getString("name"),
                        fieldJson.isNull("value") ? null : fieldJson.getString("value"),
                        !fieldJson.isNull("inline") && fieldJson.getBoolean("inline"),
                        false)); // unchecked field instantiation
            }
            embed.setFields(fields);
        }
        else embed.setFields(Collections.emptyList());
        
        if (messageEmbed.containsKey("video"))
        {
            DataObject videoJson = messageEmbed.getObject("video");
            embed.setVideoInfo(new MessageEmbed.VideoInfo(
                    videoJson.getString("url"),
                    videoJson.isNull("width") ? -1 : videoJson.getInt("width"),
                    videoJson.isNull("height") ? -1 : videoJson.getInt("height")));
        }
        return embed;
    }

    public PermissionOverride createPermissionOverride(DataObject override, Channel chan)
    {
        PermissionOverrideImpl permOverride;
        final long id = override.getLong("id");
        long allow = override.getLong("allow");
        long deny = override.getLong("deny");

        //Throwing NoSuchElementException for common issues with overrides that are not cleared properly by discord
        // when a member leaves or a role is deleted
        switch (override.getString("type"))
        {
            case "member":
                Member member = chan.getGuild().getMemberById(id);
                if (member == null)
                    throw new NoSuchElementException("Attempted to create a PermissionOverride for a non-existent user. Guild: " + chan.getGuild() + ", Channel: " + chan + ", JSON: " + override);

                permOverride = (PermissionOverrideImpl) chan.getPermissionOverride(member);
                if (permOverride == null)
                {
                    permOverride = new PermissionOverrideImpl(chan, member.getUser().getIdLong(), member);
                    ((AbstractChannelImpl<?>) chan).getOverrideMap().put(member.getUser().getIdLong(), permOverride);
                }
                break;
            case "role":
                Role role = ((GuildImpl) chan.getGuild()).getRolesMap().get(id);
                if (role == null)
                    throw new NoSuchElementException("Attempted to create a PermissionOverride for a non-existent role! JSON: " + override);

                permOverride = (PermissionOverrideImpl) chan.getPermissionOverride(role);
                if (permOverride == null)
                {
                    permOverride = new PermissionOverrideImpl(chan, role.getIdLong(), role);
                    ((AbstractChannelImpl<?>) chan).getOverrideMap().put(role.getIdLong(), permOverride);
                }
                break;
            default:
                throw new IllegalArgumentException("Provided with an unknown PermissionOverride type! JSON: " + override);
        }
        return permOverride.setAllow(allow).setDeny(deny);
    }

    public Webhook createWebhook(DataObject object)
    {
        final long id = object.getLong("id");
        final long guildId = object.getLong("guild_id");
        final long channelId = object.getLong("channel_id");
        String token = !object.isNull("token") ? object.getString("token") : null;

        TextChannel channel = api.getTextChannelById(channelId);
        if (channel == null)
            throw new NullPointerException(String.format("Tried to create Webhook for an un-cached TextChannel! WebhookId: %s ChannelId: %s GuildId: %s",
                    id, channelId, guildId));

        Object name = !object.isNull("name") ? object.get("name") : null;
        Object avatar = !object.isNull("avatar") ? object.get("avatar") : null;

        DataObject fakeUser = new DataObject()
                    .put("username", name)
                    .put("discriminator", "0000")
                    .put("id", id)
                    .put("avatar", avatar);
        User defaultUser = createFakeUser(fakeUser, false);

        DataObject ownerJson = object.getObject("user");
        final long userId = ownerJson.getLong("id");

        User owner = api.getUserById(userId);
        if (owner == null)
        {
            ownerJson.put("id", userId);
            owner = createFakeUser(ownerJson, false);
        }

        return new WebhookImpl(channel, id).setToken(token).setOwner(channel.getGuild().getMember(owner)).setUser(defaultUser);
    }

    public Relationship createRelationship(DataObject relationshipJson)
    {
        if (api.getAccountType() != AccountType.CLIENT)
            throw new AccountTypeException(AccountType.CLIENT, "Attempted to create a Relationship but the logged in account is not a CLIENT!");

        RelationshipType type = RelationshipType.fromKey(relationshipJson.getInt("type"));
        User user;
        if (type == RelationshipType.FRIEND)
            user = createUser(relationshipJson.getObject("user"));
        else
            user = createFakeUser(relationshipJson.getObject("user"), true);

        Relationship relationship = api.asClient().getRelationshipById(user.getIdLong(), type);
        if (relationship == null)
        {
            switch (type)
            {
                case FRIEND:
                    relationship = new FriendImpl(user);
                    break;
                case BLOCKED:
                    relationship = new BlockedUserImpl(user);
                    break;
                case INCOMING_FRIEND_REQUEST:
                    relationship = new IncomingFriendRequestImpl(user);
                    break;
                case OUTGOING_FRIEND_REQUEST:
                    relationship = new OutgoingFriendRequestImpl(user);
                    break;
                default:
                    return null;
            }
            ((JDAClientImpl) api.asClient()).getRelationshipMap().put(user.getIdLong(), relationship);
        }
        return relationship;
    }

    public Group createGroup(DataObject groupJson)
    {
        if (api.getAccountType() != AccountType.CLIENT)
            throw new AccountTypeException(AccountType.CLIENT, "Attempted to create a Group but the logged in account is not a CLIENT!");

        final long groupId = groupJson.getLong("id");
        DataArray recipients = groupJson.getArray("recipients");
        final long ownerId = groupJson.getLong("owner_id");
        String name = !groupJson.isNull("name") ? groupJson.getString("name") : null;
        String iconId = !groupJson.isNull("icon") ? groupJson.getString("icon") : null;
        long lastMessage = !groupJson.isNull("last_message_id") ? groupJson.getLong("last_message_id") : -1;

        GroupImpl group = (GroupImpl) api.asClient().getGroupById(groupId);
        if (group == null)
        {
            group = new GroupImpl(groupId, api);
            ((JDAClientImpl) api.asClient()).getGroupMap().put(groupId, group);
        }

        TLongObjectMap<User> groupUsers = group.getUserMap();
        groupUsers.put(api.getSelfUser().getIdLong(), api.getSelfUser());
        for (int i = 0; i < recipients.length(); i++)
        {
            DataObject groupUser = recipients.getObject(i);
            groupUsers.put(groupUser.getLong("id"), createFakeUser(groupUser, true));
        }

        User owner = api.getUserMap().get(ownerId);
        if (owner == null)
            owner = api.getFakeUserMap().get(ownerId);
        if (owner == null)
            throw new IllegalArgumentException("Attempted to build a Group, but could not find user by provided owner id." +
                    "This should not be possible because the owner should be IN the group!");

        return group
                .setOwner(owner)
                .setLastMessageId(lastMessage)
                .setName(name)
                .setIconId(iconId);
    }

    public Invite createInvite(DataObject object)
    {
        final String code = object.getString("code");

        final User inviter = object.containsKey("inviter") ? this.createFakeUser(object.getObject("inviter"), false) : null;

        final DataObject channelObject = object.getObject("channel");

        final ChannelType channelType = ChannelType.fromId(channelObject.getInt("type"));
        final long channelId = channelObject.getLong("id");
        final String channelName = channelObject.getString("name");

        final Invite.Channel channel = new InviteImpl.ChannelImpl(channelId, channelName, channelType);

        final DataObject guildObject = object.getObject("guild");

        final String guildIconId = guildObject.isNull("icon") ? null : guildObject.getString("icon");
        final long guildId = guildObject.getLong("id");
        final String guildName = guildObject.getString("name");
        final String guildSplashId = guildObject.isNull("splash") ? null : guildObject.getString("splash");

        final Invite.Guild guild = new InviteImpl.GuildImpl(guildId, guildIconId, guildName, guildSplashId);

        final int maxAge;
        final int maxUses;
        final boolean temporary;
        final OffsetDateTime timeCreated;
        final int uses;
        final boolean expanded;

        if (object.containsKey("max_uses"))
        {
            expanded = true;
            maxAge = object.getInt("max_age");
            maxUses = object.getInt("max_uses");
            uses = object.getInt("uses");
            temporary = object.getBoolean("temporary");
            timeCreated = OffsetDateTime.parse(object.getString("created_at"));
        }
        else
        {
            expanded = false;
            maxAge = -1;
            maxUses = -1;
            uses = -1;
            temporary = false;
            timeCreated = null;
        }

        return new InviteImpl(api, code, expanded, inviter, maxAge, maxUses, temporary, timeCreated, uses, channel, guild);
    }

    public void clearCache()
    {
        cachedGuildJsons.clear();
        cachedGuildCallbacks.clear();
    }

    public ApplicationInfo createApplicationInfo(DataObject object)
    {
        final String description = object.getString("description");
        final boolean doesBotRequireCodeGrant = object.getBoolean("bot_require_code_grant");
        final String iconId = !object.isNull("icon") ? object.getString("icon") : null;
        final long id = object.getLong("id");
        final String name = object.getString("name");
        final boolean isBotPublic = object.getBoolean("bot_public");
        final User owner = createFakeUser(object.getObject("owner"), false);

        return new ApplicationInfoImpl(api, description, doesBotRequireCodeGrant, iconId, id, isBotPublic, name, owner);
    }

    public Application createApplication(DataObject object)
    {
        return new ApplicationImpl(api, object);
    }

    public AuthorizedApplication createAuthorizedApplication(DataObject object)
    {
        final long authId = object.getLong("id");

        DataArray scopeArray = object.getArray("scopes");
        List<String> scopes = new ArrayList<>(scopeArray.length());
        for (int i = 0; i < scopeArray.length(); i++)
        {
            scopes.add(scopeArray.getString(i));
        }
        DataObject application = object.getObject("application");

        final String description = application.getString("description");
        final String iconId = application.containsKey("icon") ? application.getString("icon") : null;
        final long id = application.getLong("id");
        final String name = application.getString("name");

        return new AuthorizedApplicationImpl(api, authId, description, iconId, id, name, scopes);
    }

    public AuditLogEntry createAuditLogEntry(GuildImpl guild, DataObject entryJson, DataObject userJson)
    {
        final long targetId = entryJson.isNull("target_id") ? 0 : entryJson.getLong("target_id");
        final long id = entryJson.getLong("id");
        final int typeKey = entryJson.getInt("action_type");
        final DataArray changes = entryJson.isNull("changes") ? null : entryJson.getArray("changes");
        final DataObject options = entryJson.isNull("options") ? null : entryJson.getObject("options");
        final String reason = entryJson.isNull("reason") ? null : entryJson.getString("reason");

        final UserImpl user = (UserImpl) createFakeUser(userJson, false);
        final Set<AuditLogChange> changesList;
        final ActionType type = ActionType.from(typeKey);

        if (changes != null)
        {
            changesList = new HashSet<>(changes.length());
            for (int i = 0; i < changes.length(); i++)
            {
                final DataObject object = changes.getObject(i);
                AuditLogChange change = createAuditLogChange(object);
                changesList.add(change);
            }
        }
        else
        {
            changesList = Collections.emptySet();
        }

        CaseInsensitiveMap<String, AuditLogChange> changeMap = new CaseInsensitiveMap<>(changeToMap(changesList));
        CaseInsensitiveMap<String, Object> optionMap = options != null
                ? new CaseInsensitiveMap<>(options) : null;

        return new AuditLogEntry(type, id, targetId, guild, user, reason, changeMap, optionMap);
    }

    public AuditLogChange createAuditLogChange(DataObject change)
    {
        final String key = change.getString("key");
        Object oldValue = change.isNull("old_value") ? null : change.get("old_value");
        Object newValue = change.isNull("new_value") ? null : change.get("new_value");

        // Don't confront users with JSON
        if (oldValue instanceof DataArray || newValue instanceof DataArray)
        {
            oldValue = oldValue instanceof DataArray ? (DataArray) oldValue : oldValue;
            newValue = newValue instanceof DataArray ? (DataArray) newValue : newValue;
        }
        else if (oldValue instanceof DataObject || newValue instanceof DataObject)
        {
            oldValue = oldValue instanceof DataObject ? (DataObject) oldValue : oldValue;
            newValue = newValue instanceof DataObject ? (DataObject) newValue : newValue;
        }

        return new AuditLogChange(oldValue, newValue, key);
    }

    private Map<String, AuditLogChange> changeToMap(Set<AuditLogChange> changesList)
    {
        return changesList.stream().collect(Collectors.toMap(AuditLogChange::getKey, UnaryOperator.identity()));
    }
}
