package cn.myflycat.bcstock.data;

/**
 * 一家公司的服务端地板行。{@code floor} 由服务端算好，<b>不反推</b>。
 * 不引用 {@code net.minecraft}。
 */
public record FloorView(
        int companyId,
        String companyName,
        double floor,
        double current,
        double distancePct,
        double dangerZone,
        boolean inDangerZone,
        double crashRiskPerTick,
        String status) {

    public boolean known() {
        return companyName != null && !companyName.isBlank()
                && !Double.isNaN(floor) && floor > 0
                && !Double.isNaN(distancePct);
    }

    /** 距地板 &lt; 1%（服务端 distance_pct）。 */
    public boolean nearFloor() {
        return known() && distancePct < 1.0;
    }
}
