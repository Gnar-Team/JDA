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

package net.dv8tion.jda.core.handle;

import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.impl.JDAImpl;
import net.dv8tion.jda.core.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.core.utils.data.DataArray;
import net.dv8tion.jda.core.utils.data.DataObject;

import java.util.LinkedList;

public class MessageBulkDeleteHandler extends SocketHandler
{
    public MessageBulkDeleteHandler(JDAImpl api)
    {
        super(api);
    }

    @Override
    protected Long handleInternally(DataObject content)
    {
        final long channelId = content.getLong("channel_id");

        DataArray ids = content.getArray("ids");

        if (api.isBulkDeleteSplittingEnabled())
        {
            SocketHandler handler = api.getClient().getHandler("MESSAGE_DELETE");
            ids.forEach(id ->
            {
<<<<<<< HEAD
                handler.handle(responseNumber, new DataObject()
                    .put("d", new DataObject()
=======
                handler.handle(responseNumber, new DataObject()
                    .put("t", "MESSAGE_DELETE")
                    .put("d", new DataObject()
>>>>>>> master
                        .put("channel_id", Long.toUnsignedString(channelId))
                        .put("id", id)));
            });
        }
        else
        {
            TextChannel channel = api.getTextChannelMap().get(channelId);
            if (channel == null)
            {
                api.getEventCache().cache(EventCache.Type.CHANNEL, channelId, () -> handle(responseNumber, allContent));
                EventCache.LOG.debug("Received a Bulk Message Delete for a TextChannel that is not yet cached.");
                return null;
            }

            if (api.getGuildLock().isLocked(channel.getGuild().getIdLong()))
            {
                return channel.getGuild().getIdLong();
            }

            LinkedList<Long> msgIds = new LinkedList<>();
            for (int i = 0; i < ids.size(); i++)
            {
                msgIds.add(ids.getLong(i));
            }

            api.getEventManager().handle(
                    new MessageBulkDeleteEvent(
                            api, responseNumber,
                            channel, msgIds));
        }
        return null;
    }
}
