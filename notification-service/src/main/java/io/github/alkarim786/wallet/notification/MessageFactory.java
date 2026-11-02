package io.github.alkarim786.wallet.notification;

import io.github.alkarim786.wallet.common.TransferEvent;

import java.util.ArrayList;
import java.util.List;

/** Turns a wallet event into the notifications each affected user should receive. Pure, so it is unit-tested directly. */
public final class MessageFactory {

    public record Draft(long userId, String kind, String message) {}

    private MessageFactory() {}

    public static String money(long minor) {
        return String.format("$%d.%02d", minor / 100, minor % 100);
    }

    public static List<Draft> draftsFor(TransferEvent e) {
        List<Draft> out = new ArrayList<>(2);
        String amount = money(e.amountMinor());
        switch (e.type()) {
            case "TRANSFER_COMPLETED" -> {
                add(out, e.fromUserId(), "SENT", "You sent " + amount + ".");
                add(out, e.toUserId(), "RECEIVED", "You received " + amount + ".");
            }
            case "TRANSFER_RELEASED" -> {
                add(out, e.fromUserId(), "SENT", "Your held transfer of " + amount + " was approved and sent.");
                add(out, e.toUserId(), "RECEIVED", "You received " + amount + ".");
            }
            case "TRANSFER_HELD" ->
                add(out, e.fromUserId(), "HELD", "Your transfer of " + amount + " is under review and has not been sent yet.");
            case "TRANSFER_DECLINED" ->
                add(out, e.fromUserId(), "DECLINED", "Your transfer of " + amount + " was declined for security reasons.");
            case "TRANSFER_REJECTED" ->
                add(out, e.fromUserId(), "REJECTED", "Your held transfer of " + amount + " was rejected after review. No money moved.");
            case "TOPUP_COMPLETED" ->
                add(out, e.toUserId(), "TOPUP", "Your wallet was topped up with " + amount + ".");
            default -> { /* unknown event types are ignored so a newer producer cannot break this consumer */ }
        }
        return out;
    }

    private static void add(List<Draft> out, Long userId, String kind, String message) {
        if (userId != null) {
            out.add(new Draft(userId, kind, message));
        }
    }
}
