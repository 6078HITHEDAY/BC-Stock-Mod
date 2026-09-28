package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.TradeDraft;

/**
 * 账本里的一笔买卖。状态机见 {@link Status}。
 * 不引用 {@code net.minecraft}。
 */
public record OrderRecord(
        long id,
        TradeDraft.Side side,
        int marketId,
        String companyName,
        int qty,
        Origin origin,
        Status status,
        long createdAt,
        Long sentAt,
        Long filledAt,
        Double fillPrice,
        String failReason) {

    public enum Origin {
        MANUAL,
        AUTO
    }

    public enum Status {
        PENDING,
        SENT,
        CHAT_OK,
        CHAT_FAIL,
        UNCONFIRMED,
        RECONCILED,
        CANCELLED
    }

    public OrderRecord {
        if (side == null) {
            side = TradeDraft.Side.BUY;
        }
        if (origin == null) {
            origin = Origin.MANUAL;
        }
        if (status == null) {
            status = Status.PENDING;
        }
        if (companyName == null) {
            companyName = "";
        }
        if (failReason == null) {
            failReason = "";
        }
    }

    public boolean countsTowardDailyCap() {
        return status == Status.CHAT_OK || status == Status.RECONCILED;
    }
}
