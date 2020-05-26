package io.coti.basenode.services.interfaces;

import io.coti.basenode.data.messages.GeneralVoteMessage;
import io.coti.basenode.data.Hash;
import io.coti.basenode.data.messages.StateMessage;

public interface IGeneralVoteService {

    void init();

    void handleGeneralVoting(GeneralVoteMessage generalVoteMessage);

    void startCollectingVotes(StateMessage voteHash);

    void castVoteForClusterstampIndex(Hash voteHash, boolean vote);
}
