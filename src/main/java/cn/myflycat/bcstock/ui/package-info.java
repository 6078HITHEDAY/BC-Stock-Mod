/**
 * 展示层。界面只吃 {@link cn.myflycat.bcstock.data.StockSnapshot} / {@link cn.myflycat.bcstock.data.DegradePolicy}，
 * <b>不许出现</b> {@code HttpClient} / {@code ScreenHandler} / {@code CommandGateway}。
 *
 * <p>A1：{@link cn.myflycat.bcstock.ui.StockBoardScreen} + K 键。
 * 布局数字在 {@link cn.myflycat.bcstock.ui.UiPalette}，不引用 {@code net.minecraft} 的部分可离线测。
 */
package cn.myflycat.bcstock.ui;
