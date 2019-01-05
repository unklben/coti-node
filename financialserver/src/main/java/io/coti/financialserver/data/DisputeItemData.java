package io.coti.financialserver.data;

import io.coti.basenode.data.Hash;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class DisputeItemData implements Serializable {
    @NotNull
    private Long id;
    private BigDecimal price;
    private int quantity;
    @NotNull
    private DisputeReason reason;
    private DisputeItemStatus status;
    private List<Hash> disputeDocumentHashes;
    private List<Hash> disputeCommentHashes;
    private List<DisputeItemVoteData> disputeItemVotesData;
    private Date arbitratorsDecisionTime;

    public DisputeItemData() {
        disputeDocumentHashes = new ArrayList<>();
        disputeCommentHashes = new ArrayList<>();
        disputeItemVotesData = new ArrayList<>();
        status = DisputeItemStatus.Recall;
    }

    public void addDocumentHash(Hash documentHash) {
        disputeDocumentHashes.add(documentHash);
    }

    public void addCommentHash(Hash commentHash) {
        disputeCommentHashes.add(commentHash);
    }

    public void addItemVoteData(DisputeItemVoteData disputeItemVoteData) {
        disputeItemVotesData.add(disputeItemVoteData);
    }

    public Boolean arbitratorAlreadyVoted(Hash arbitratorHash) {
        for (DisputeItemVoteData disputeItemVoteData : disputeItemVotesData) {
            if (disputeItemVoteData.getArbitratorHash().equals(arbitratorHash)) {
                return true;
            }
        }
        return false;
    }
}
