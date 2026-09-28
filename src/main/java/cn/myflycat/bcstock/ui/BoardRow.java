package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.decision.DecisionEngine;

/**
 * 一行要画什么。界面只吃这个，不碰网络 / 容器。
 *
 * <p>{@link #name()} 永远是公司名（查找 / 选中用）。盘面显示用 {@link #text(BoardColumns.Col)}，
 * 买/卖建议加在公司名前，观望不加字。
 */
public record BoardRow(
        String name,
        String priceText,
        String changeText,
        String riskText,
        String sharesText,
        String costText,
        String profitText,
        int nameColor,
        int priceColor,
        int changeColor,
        int riskColor,
        int sharesColor,
        int costColor,
        int profitColor,
        boolean bankrupt,
        boolean held,
        double sortChange,
        int sortRisk,
        double sortProfit,
        String namePrefix) {

    public static BoardRow from(CompanyView company, HoldingView holding) {
        return from(company, holding, null);
    }

    public static BoardRow from(CompanyView company, HoldingView holding, DecisionEngine.Advice advice) {
        if (company == null) {
            throw new IllegalArgumentException("公司行不能为空");
        }
        boolean bankrupt = company.bankrupt();
        int body = bankrupt ? UiPalette.MUTED : UiPalette.TEXT;
        String priceText = bankrupt ? BoardFormat.BANKRUPT
                : (company.priceKnown() ? BoardFormat.price(company.price()) : BoardFormat.UNKNOWN);
        String changeText;
        int changeColor;
        if (bankrupt || Double.isNaN(company.changePct())) {
            changeText = bankrupt ? BoardFormat.DASH : BoardFormat.UNKNOWN;
            changeColor = UiPalette.MUTED;
        } else if (company.changePct() > 0) {
            changeText = BoardFormat.pct(company.changePct());
            changeColor = UiPalette.UP;
        } else if (company.changePct() < 0) {
            changeText = BoardFormat.pct(company.changePct());
            changeColor = UiPalette.DOWN;
        } else {
            changeText = BoardFormat.pct(0);
            changeColor = UiPalette.MUTED;
        }

        String riskText = company.risk() > 0 ? Integer.toString(company.risk()) : BoardFormat.UNKNOWN;
        int riskColor = (company.risk() >= 4) ? UiPalette.DOWN : UiPalette.MUTED;
        if (bankrupt) {
            riskColor = UiPalette.MUTED;
        }

        boolean held = holding != null && holding.held();
        String sharesText = held ? BoardFormat.shares(holding.shares()) : BoardFormat.DASH;
        String costText = BoardFormat.DASH;
        String profitText = BoardFormat.DASH;
        int profitColor = UiPalette.MUTED;
        if (held) {
            if (holding.averageBuyPriceKnown()) {
                costText = BoardFormat.price(holding.averageBuyPrice());
                if (Double.isNaN(holding.profitPct())) {
                    profitText = BoardFormat.UNKNOWN;
                } else {
                    profitText = BoardFormat.pct(holding.profitPct());
                    if (holding.profitPct() > 0) {
                        profitColor = UiPalette.UP;
                    } else if (holding.profitPct() < 0) {
                        profitColor = UiPalette.DOWN;
                    }
                }
            } else {
                costText = BoardFormat.UNKNOWN;
                profitText = BoardFormat.UNKNOWN;
            }
        }
        if (bankrupt) {
            profitColor = UiPalette.MUTED;
        }
        String prefix = (advice == null) ? "" : advice.prefix();
        int paintedName = body;
        if (!bankrupt && advice != null && advice.side() == DecisionEngine.Side.BUY) {
            paintedName = UiPalette.UP;
        } else if (!bankrupt && advice != null && advice.side() == DecisionEngine.Side.SELL) {
            paintedName = UiPalette.DOWN;
        }
        return new BoardRow(
                company.name(),
                priceText,
                changeText,
                riskText,
                sharesText,
                costText,
                profitText,
                paintedName,
                body,
                bankrupt ? UiPalette.MUTED : changeColor,
                riskColor,
                body,
                UiPalette.MUTED,
                bankrupt ? UiPalette.MUTED : profitColor,
                bankrupt,
                held,
                bankrupt || Double.isNaN(company.changePct()) ? Double.NaN : company.changePct(),
                company.risk(),
                (held && holding.averageBuyPriceKnown() && !Double.isNaN(holding.profitPct()))
                        ? holding.profitPct() : Double.NaN,
                prefix);
    }

    public String text(BoardColumns.Col col) {
        return switch (col) {
            case NAME -> (namePrefix == null || namePrefix.isBlank()) ? name : namePrefix + name;
            case PRICE -> priceText;
            case CHANGE -> changeText;
            case RISK -> riskText;
            case SHARES -> sharesText;
            case COST -> costText;
            case PROFIT -> profitText;
            case ACTION -> "";
        };
    }

    public int color(BoardColumns.Col col) {
        return switch (col) {
            case NAME -> nameColor;
            case PRICE -> priceColor;
            case CHANGE -> changeColor;
            case RISK -> riskColor;
            case SHARES -> sharesColor;
            case COST -> costColor;
            case PROFIT -> profitColor;
            case ACTION -> UiPalette.MUTED;
        };
    }
}
