package com.example.ykdsummer.reminder.persistence;

import java.util.Optional;

/** Durable, non-public storage for the latest iLink reply context of a chat user. */
public interface ILinkReplyContextPersistence {
    void save(String externalUserId, String contextToken);
    Optional<String> find(String externalUserId);
}
