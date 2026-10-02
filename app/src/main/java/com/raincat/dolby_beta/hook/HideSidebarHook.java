package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.DebugLogger;
import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.model.SidebarEnum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat & Cisco
 *     desc   : 精简侧边栏 (视图层方案)
 *     version: 4.0
 *
 *     重构说明:
 *     9.6.x 的侧边栏内容不经过 account.j/AccountItem 数据流, 也不在 link/position JSON 中,
 *     数据源随版本变化不可依赖 —— 因此改为数据源无关的视图层方案:
 *     锚定 MainDrawer 控制器 (refreshDrawer()/sideView 字段), 在侧边栏渲染后遍历其视图树,
 *     对文本命中已勾选条目关键词的行整行折叠 (GONE + 0 高度)。原生行与 RN 文本行
 *     (ReactTextView 是 TextView 子类) 均被覆盖。
 * </pre>
 */
public class HideSidebarHook {
    private static final String TAG = "HideSidebarHook";
    private static final String DRAWER_CONTROLLER_CLASS = "com.netease.cloudmusic.music.biz.sidebar.ui.MainDrawer";

    /** 上次扫描时间戳, 去抖 */
    private static long lastScanAt;
    /** 已折叠的行, 避免重复操作与日志刷屏 */
    private static final Set<View> collapsedRows = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public HideSidebarHook(Context context, int versionCode) {
        // 1. 视图层方案: 锚定 MainDrawer 控制器
        //    (条目键空间唯一 = 条目名: 动态识别 + 持久化 + 启动期名称表补位, 见 SidebarEnum)
        Class<?> drawerClass = XposedHelpers.findClassIfExists(DRAWER_CONTROLLER_CLASS, context.getClassLoader());
        if (drawerClass == null) {
            DebugLogger.w(TAG, "MainDrawer controller class not found, sidebar hide inactive");
            return;
        }

        XC_MethodHook scanHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                sControllerRef.set(param.thisObject);
                scheduleScan(param.thisObject);
            }
        };

        // 2a. 控制器构造完成 (sideView 可能尚未 attach, 扫描内部会自行等待)
        try {
            XposedBridge.hookAllConstructors(drawerClass, scanHook);
        } catch (Throwable ignored) {
        }

        // 2b. refreshDrawer() 每次刷新侧边栏内容后重新扫描
        try {
            for (Method m : drawerClass.getDeclaredMethods()) {
                if ("refreshDrawer".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, scanHook);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2c. 关键补漏: 抽屉滑出/完全打开时触发扫描。
        // 否则扫描只发生在启动后的头几秒 —— 用户之后在模块对话框里勾选了条目,
        // 重新打开抽屉时不会再有任何扫描, 勾选永远不生效。
        try {
            Class<?> toggleClass = XposedHelpers.findClassIfExists(
                    "androidx.appcompat.app.ActionBarDrawerToggle", context.getClassLoader());
            if (toggleClass != null) {
                XC_MethodHook drawerOpenHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        // 抽屉完全打开后延迟一拍内容才绑定完成, 只保留一次延迟扫描 (避免掉帧)
                        View drawerView = param.args != null && param.args.length > 0 && param.args[0] instanceof View
                                ? (View) param.args[0] : null;
                        if (drawerView != null) {
                            drawerView.postDelayed(() -> scanDirect(drawerView), 600);
                        } else {
                            scheduleScan(sControllerRef.get());
                        }
                    }
                };
                // 只在"完全打开"时触发: onDrawerSlide 在滑动动画每帧都回调,
                // 每帧扫描 165 视图会造成抽屉滑动掉帧, 必须移除
                XposedBridge.hookAllMethods(toggleClass, "onDrawerOpened", drawerOpenHook);
                DebugLogger.i(TAG, "drawer open scan trigger registered");
            }
        } catch (Throwable t) {
            DebugLogger.d(TAG, "drawer trigger registration failed: " + t);
        }

        // 2d. 再兜底: DrawerLayout.openDrawer 打开动作直接触发扫描 (不依赖应用自己的监听器)
        try {
            Class<?> drawerLayoutClass = XposedHelpers.findClassIfExists(
                    "androidx.drawerlayout.widget.DrawerLayout", context.getClassLoader());
            if (drawerLayoutClass != null) {
                XposedBridge.hookAllMethods(drawerLayoutClass, "openDrawer", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        Object first = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (first instanceof View) {
                            View dv = (View) first;
                            dv.postDelayed(() -> scanDirect(dv), 600);
                        } else {
                            scheduleScan(sControllerRef.get());
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        DebugLogger.i(TAG, "sidebar view-scan hooks registered on " + drawerClass.getName());

        // 3. 原生行渲染入口拦截 (首选手段, 数据级): ViewHolder.render(AccountItem, int, wb.a)
        //    在渲染时就判定隐藏 → 整行不渲染: 无残留空洞, 且因为不在原生树上故无法点击
        //    (RN/原生触摸都可能按"数据位置"命中, 视图层 GONE 不足以阻止点击)。
        //    渲染入口由 DexKit 结构特征匹配 (sidebar.account 包内 render(AccountItem,·,·) void),
        //    依赖类解析完成, 因此挂到解析后任务。
        ClassHelper.runAfterResolve(() -> installDrawerItemRenderHooks(context));
    }

    private static void installDrawerItemRenderHooks(Context context) {
        try {
            Class<?> itemClass = ClassHelper.SidebarDrawerItem.getClazz(context);
            List<Method> renders = ClassHelper.SidebarDrawerItem.getRenderMethods(context);
            if (itemClass == null || renders.isEmpty()) {
                DebugLogger.w(TAG, "drawer render entries not found (fallback: view scan only)");
                return;
            }
            Method getEnum = ClassHelper.SidebarDrawerItem.getEnumTypeMethod(context);
            Method getData = ClassHelper.SidebarDrawerItem.getDataMethod(context);
            Method getExtra = ClassHelper.SidebarDrawerItem.getExtraMethod(context);
            int hooked = 0;
            for (Method render : renders) {
                try {
                    XposedBridge.hookMethod(render, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!SettingHelper.getInstance().isSidebarHideEnable()) return;
                                Object item = param.args != null && param.args.length > 0 ? param.args[0] : null;
                                if (item == null || !itemClass.isInstance(item)) return;
                                boolean hide = shouldHideDrawerItem(item, getEnum, getData, getExtra);
                                if (!hide) {
                                    // 数据模型 toString 通常不含标题 —— 渲染完成后按行内实际文本再判一次
                                    // (ViewHolder 已把标题写入 TextView, 与设置对话框勾选的条目名一致)
                                    hide = shouldHideByRenderedText(param.thisObject);
                                }
                                applyHolderState(param.thisObject, hide);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            DebugLogger.i(TAG, "drawer render hooks installed: " + hooked + "/" + renders.size());
        } catch (Throwable t) {
            DebugLogger.e(TAG, "installDrawerItemRenderHooks error: " + t.getMessage(), t);
        }
    }

    /** 条目是否命中隐藏清单: 枚举名 (STORE/MESSAGE/...) 精确匹配 + 条目文本关键词/条目名匹配 */
    private static boolean shouldHideDrawerItem(Object item, Method getEnum, Method getData, Method getExtra) {
        HashMap<String, Boolean> map = SettingHelper.getInstance().getSidebarSetting(null);
        if (map == null || map.isEmpty()) return false;
        StringBuilder text = new StringBuilder();
        try {
            if (getEnum != null) {
                Object e = getEnum.invoke(item);
                if (e instanceof Enum) {
                    String name = ((Enum<?>) e).name();
                    if (Boolean.TRUE.equals(map.get(name))) return true;
                    text.append(name).append(' ');
                } else if (e != null) {
                    text.append(e).append(' ');
                }
            }
        } catch (Throwable ignored) {
        }
        for (Method g : new Method[]{getData, getExtra}) {
            if (g == null) continue;
            try {
                Object v = g.invoke(item);
                if (v != null) text.append(v).append(' ');
            } catch (Throwable ignored) {
            }
        }
        String all = text.toString();
        // 已勾选的条目名 (键空间统一为条目名)
        for (HashMap.Entry<String, Boolean> e : map.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) continue;
            String name = e.getKey();
            if (name == null || name.trim().length() < 2) continue;
            if (all.contains(name.trim())) return true;
        }
        return false;
    }

    /**
     * 按"渲染后行内实际文本"判定隐藏 (ViewHolder.render 完成后调用)。
     * 侧边栏条目标题只存在于渲染出的 TextView 中 (数据模型 toString 不含),
     * 因此这一层是原生条目命中率最高的判定。
     */
    private static boolean shouldHideByRenderedText(Object holder) {
        try {
            Object v = XposedHelpers.getObjectField(holder, "itemView");
            if (!(v instanceof View)) return false;
            HashMap<String, Boolean> map = SettingHelper.getInstance().getSidebarSetting(null);
            if (map == null || map.isEmpty()) return false;
            List<TextView> texts = new java.util.ArrayList<>();
            collectTextViews((View) v, texts, 0);
            for (TextView tv : texts) {
                CharSequence cs = tv.getText();
                if (cs == null) continue;
                String text = cs.toString().trim();
                if (text.isEmpty() || text.length() > 16) continue;
                if (shouldHideSidebarText(text, map)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void collectTextViews(View view, List<TextView> out, int depth) {
        if (view == null || depth > 6 || out.size() >= 12) return;
        if (view instanceof TextView) {
            out.add((TextView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTextViews(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** 记录 itemView 被修改前的原始状态 (RecyclerView 复用视图, 每次绑定都要按条目重新设置) */
    private static final java.util.WeakHashMap<View, int[]> sOriginalState = new java.util.WeakHashMap<>();

    /**
     * 按条目判定结果设置 ViewHolder 行视图状态。
     * RecyclerView 会复用 itemView, 因此每个条目绑定 (render) 时都必须显式恢复/折叠:
     * 隐藏 → GONE + 高度 0 (行被移出布局, 无空洞且不可点击);
     * 未命中 → 恢复原始可见性/高度/可点击性。
     */
    private static void applyHolderState(Object holder, boolean hide) {
        try {
            Object v = XposedHelpers.getObjectField(holder, "itemView");
            if (!(v instanceof View)) return;
            View itemView = (View) v;
            if (hide) {
                if (collapsedRows.add(itemView)) {
                    DebugLogger.i(TAG, "hide drawer item(render): " + itemView.getClass().getSimpleName());
                }
                collapseView(itemView);
                itemView.requestLayout();
            } else {
                restoreView(itemView);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 恢复被本模块折叠/禁用的视图 (复用场景): 可见 + 原始高度 + 可点击 */
    private static void restoreView(View view) {
        if (view == null) return;
        int[] orig = sOriginalState.get(view);
        boolean dirty = view.getVisibility() != View.VISIBLE;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (orig != null && lp != null && lp.height != orig[0]) {
            lp.height = orig[0];
            view.setLayoutParams(lp);
            dirty = true;
        }
        if (view.getTranslationY() != 0f) {
            // 折叠时命中框被移出屏幕, 恢复条目时一并归位
            view.setTranslationY(0f);
            dirty = true;
        }
        if (dirty) {
            view.setVisibility(View.VISIBLE);
            view.setClickable(true);
            view.setEnabled(true);
            view.requestLayout();
        }
    }

    private static final java.util.WeakHashMap<Context, Object> unusedRef = new java.util.WeakHashMap<>();
    private static final java.util.concurrent.atomic.AtomicReference<Object> sControllerRef =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * 直接按视图扫描 (不经过控制器), 供抽屉打开触发使用。
     * 注意: 功能关闭时也必须进入 scanDrawerTree —— 里面有"整体还原"逻辑,
     * 负责恢复此前被折叠的行与被关闭的滚动开关。
     */
    private static void scanDirect(View drawerView) {
        try {
            if (drawerView == null) return;
            scanDrawerTree(drawerView);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从控制器实例取侧边栏视图并安排扫描。
     * sideView 字段按名称解析, 失效后按唯一 View 类型实例字段兜底。
     */
    private static void scheduleScan(final Object controller) {
        if (controller == null) return;
        try {
            View sideView = findSideView(controller);
            if (sideView == null) return;
            final View sideViewRoot = sideView;
            // 多拍扫描: RN 在打开后会重排/重渲染 (会重置 visibility 与 translation),
            // 后几拍负责把被重置的行重新折叠并重新补位
            sideViewRoot.postDelayed(() -> scanDrawerTree(sideViewRoot), 600);
            sideViewRoot.postDelayed(() -> scanDrawerTree(sideViewRoot), 2000);
            sideViewRoot.postDelayed(() -> scanDrawerTree(sideViewRoot), 3500);
        } catch (Throwable t) {
            DebugLogger.d(TAG, "scheduleScan error: " + t);
        }
    }

    /**
     * 设置变化广播回调 (主线程): 精简侧边栏的开关/勾选变化后立即重扫。
     * 关闭功能或取消勾选时, 扫描内的"整体还原"逻辑会恢复被折叠的行与滚动开关,
     * 不必等抽屉重新打开。
     */
    public static void onSidebarSettingChanged() {
        try {
            Object controller = sControllerRef.get();
            if (controller != null) scheduleScan(controller);
        } catch (Throwable ignored) {
        }
    }

    private static View findSideView(Object controller) {
        try {
            Field f = controller.getClass().getDeclaredField("sideView");
            f.setAccessible(true);
            Object v = f.get(controller);
            if (v instanceof View) return (View) v;
        } catch (Throwable ignored) {
        }
        // 形状兜底: 第一个 View 类型的实例字段
        for (Class<?> c = controller.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(controller);
                    if (v instanceof View) return (View) v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static final java.util.Set<String> sKnownItems = new HashSet<>();
    private static final StringBuilder sNewItems = new StringBuilder();

    /**
     * 遍历侧边栏视图树:
     * 阶段一(识别): 收集抽屉里所有短文本条目 -> 存入 SidebarEnum 动态清单(设置对话框可见) + 日志;
     * 阶段二(隐藏): 仅当条目命中勾选项时, 折叠其所在的"行级"容器。
     * 安全护栏: 行容器必须满足 高度 0<h<=400px、仅含 1~3 个文本、且不是抽屉根的直接子级整块区域;
     *          不满足护栏的命中项一律放弃隐藏 (宁可藏不掉, 不可把抽屉弄坏)。
     */
    private static void scanDrawerTree(View root) {
        try {
            if (root == null) return;
            HashMap<String, Boolean> map = SettingHelper.getInstance().getSidebarSetting(null);
            boolean featureOn = SettingHelper.getInstance().isSidebarHideEnable()
                    && map != null && !map.isEmpty();
            if (!featureOn) {
                // 功能关闭/无勾选: 恢复此前折叠的行与滚动开关 (关掉功能必须真正回到原状)
                restoreAll(root);
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastScanAt < 800) return; // 去抖 (抽屉打开动画期间避免重复遍历)
            lastScanAt = now;
            int[] stats = new int[]{0, 0};
            // 诊断: 打印本次扫描时的勾选键 (确认 hook 侧能看到对话框写入的状态)
            StringBuilder checked = new StringBuilder();
            for (HashMap.Entry<String, Boolean> e : map.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    if (checked.length() > 0) checked.append(',');
                    checked.append(e.getKey());
                }
            }
            DebugLogger.d(TAG, "scan start: checkedKeys=[" + checked + "]");
            // 每轮扫描先整体还原到原始状态 (可见性/高度/margin/位移/滚动开关),
            // 再按当前勾选重新隐藏 —— 取消勾选的条目因此能恢复显示
            restoreAll(root);
            scanView(root, root, map, stats, 0);
            // 补位: 折叠行残留的空间 (绝对布局不会自动补位) 由下方兄弟累计上移补齐, 并收缩固定高度容器
            int closed = reflowVoids(root, root, 0);
            if (closed > 0) {
                DebugLogger.i(TAG, "reflowVoids: shifted=" + closed);
            }
            dumpTreeOnce(root);
            synchronized (sNewItems) {
                if (sNewItems.length() > 0) {
                    DebugLogger.i(TAG, "sidebar items detected: " + sNewItems);
                    sNewItems.setLength(0);
                    SidebarEnum.notifyDynamicChanged();
                }
            }
            // 动态识别条目持久化: 重启后对话框清单立即可用
            try {
                SettingHelper.getInstance().saveSidebarDynamicItems(SidebarEnum.getDynamicLabels());
            } catch (Throwable ignored) {
            }
            if (stats[1] > 0) {
                DebugLogger.i(TAG, "scan done: views=" + stats[0] + " rows hidden=" + stats[1]);
            }
        } catch (Throwable t) {
            DebugLogger.d(TAG, "scanDrawerTree error: " + t);
        }
    }

    private static void scanView(View view, View root, HashMap<String, Boolean> map, int[] stats, int depth) {
        if (view == null || depth > 30) return;
        // 已折叠 (GONE) 的子树一律跳过: 重复进入会把已隐藏的行当成"新发现"再次处理,
        // 造成二次位移/高度错乱 (布局"奇怪"的根因之一)
        if (view.getVisibility() != View.VISIBLE) return;
        stats[0]++;
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            String text = cs == null ? "" : cs.toString().trim();
            if (!text.isEmpty() && text.length() >= 2 && text.length() <= 14
                    && !text.contains("¥") && !text.matches("^[0-9Lv.\\s]+$")
                    && !text.contains(" - ") && !text.contains("+")
                    && !text.equals("正在加载") && !text.equals("搜索")) {
                // 阶段一: 识别项目 (每种只记录一次)
                synchronized (sKnownItems) {
                    if (sKnownItems.add(text)) {
                        SidebarEnum.addDynamicItems(java.util.Collections.singletonList(text));
                        synchronized (sNewItems) {
                            if (sNewItems.length() > 0) sNewItems.append(", ");
                            sNewItems.append(text);
                        }
                    }
                }
                // 阶段二: 命中勾选项 -> 折叠所在行
                if (shouldHideSidebarText(text, map)) {
                    hideSidebarRow(view, root, text, stats);
                    return; // 无论是否成功折叠, 该文本子树不再处理
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                scanView(vg.getChildAt(i), root, map, stats, depth + 1);
            }
        }
    }

    /**
     * 隐藏条目所在的行 (不依赖"行容器"识别, 适配 RN 扁平布局):
     * 1) 若文本的父容器本身是行级高度 (≤ 文本高*4+120), 直接折叠父容器;
     * 2) 否则 (父容器是整块内容区, RN 常见) 折叠: 文本自身 + 父容器中
     *    与文本垂直范围重叠、且高度为行级的兄弟视图 (同行图标/箭头/背景)。
     * 安全护栏: 只折叠行级高度的视图, 整块内容区(数千 px)绝不触碰。
     */
    private static void hideSidebarRow(View textView, View root, String text, int[] stats) {
        try {
            int textH = Math.max(textView.getHeight(), 40);
            int rowMaxH = textH * 4 + 120;
            int flatMaxH = Math.max(rowMaxH * 3, 700); // 扁平结构中整行(图标/背景/触摸层)高度上限

            // 0) RecyclerView 行: 行高与点击都由 itemView 承载,
            //    只折叠行内视图会留下空行且仍可点击 —— 必须折叠 itemView 本身
            View rvItem = findRecyclerItemView(textView, root);
            if (rvItem != null) {
                int rvH = Math.max(rvItem.getHeight(), textH);
                if (collapsedRows.add(rvItem)) {
                    DebugLogger.i(TAG, "hide sidebar item(rv h=" + rvH + "): " + text);
                    stats[1]++;
                }
                collapseView(rvItem);
                rvItem.requestLayout();
                // 列表外层若为固定高度容器, 由 reflowVoids 在扫描末尾统一收缩
                return;
            }

            // 1) 行容器 = 最外层"行级高度"祖先 (≤ rowMaxH)
            View anchor = null;
            ViewParent p = textView.getParent();
            int guard = 0;
            while (p instanceof View && guard++ < 20) {
                View pv = (View) p;
                if (pv == root) break;
                int h = pv.getHeight();
                if (h > 0 && h <= rowMaxH) {
                    anchor = pv;
                    p = pv.getParent();
                    continue;
                }
                break;
            }

            if (anchor != null && anchor != textView) {
                int rowH = Math.max(anchor.getHeight(), 1);
                int top = anchor.getTop(), bottom = anchor.getBottom();
                boolean first = collapsedRows.add(anchor);

                collapseView(anchor);
                anchor.requestLayout();

                ViewParent ap = anchor.getParent();
                if (ap instanceof ViewGroup && (View) ap != root) {
                    ViewGroup parent = (ViewGroup) ap;
                    int hidden = 0;
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View child = parent.getChildAt(i);
                        if (child == anchor || child == root) continue;
                        int ch = child.getHeight();
                        if (ch <= 0 || ch > rowMaxH) continue;
                        if (child.getBottom() > top && child.getTop() < bottom) {
                            collapseView(child);
                            hidden++;
                        }
                    }
                    // 空行补位统一交给扫描末尾的 reflowVoids
                    if (first) {
                        DebugLogger.i(TAG, "hide sidebar row(h=" + rowH + " siblings=" + hidden + "): " + text);
                    }
                } else if (first) {
                    DebugLogger.i(TAG, "hide sidebar row(h=" + rowH + "): " + text);
                }
                if (first) stats[1]++;

                // 可点击祖先 (触摸层) 一并折叠禁用, 保证隐藏后不可点击
                View v = anchor;
                for (int i = 0; i < 10 && v != null && v != root; i++) {
                    if (isClickableView(v) && v.getHeight() <= flatMaxH) {
                        collapseView(v);
                        break;
                    }
                    ViewParent vp = v.getParent();
                    if (!(vp instanceof View)) break;
                    v = (View) vp;
                }
                return;
            }

            // 2) 扁平结构 (RN 绝对布局): 定位"行带" → 折叠行带内全部内容 → 下方行上移补位
            flatHideRow(textView, root, text, rowMaxH, flatMaxH, stats);
        } catch (Throwable t) {
            DebugLogger.d(TAG, "hideSidebarRow error: " + t);
        }
    }

    /** 是否为 RecyclerView 的 itemView (最近的 RecyclerView 直接子级) */
    private static View findRecyclerItemView(View textView, View root) {
        View cur = textView;
        int guard = 0;
        while (cur != null && cur != root && guard++ < 30) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return null;
            View pv = (View) p;
            if (pv == root) return null;
            if (pv.getClass().getName().contains("RecyclerView")) return cur;
            cur = pv;
        }
        return null;
    }

    /** 是否可点击 (含 setOnClickListener 但 clickable 未置位的情况) */
    private static boolean isClickableView(View v) {
        try {
            if (v.isClickable()) return true;
            Method m = View.class.getDeclaredMethod("getListenerInfo");
            m.setAccessible(true);
            Object li = m.invoke(v);
            if (li != null) {
                Field f = li.getClass().getDeclaredField("mOnClickListener");
                f.setAccessible(true);
                return f.get(li) != null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 布局是否会因 GONE 自动重排 (LinearLayout/RecyclerView 会; RN/FrameLayout 不会) */
    private static boolean autoReflow(ViewGroup parent) {
        String pn = parent.getClass().getName();
        return pn.contains("LinearLayout") || pn.contains("RecyclerView")
                || pn.contains("ListView") || pn.contains("TableLayout");
    }

    /**
     * RN 扁平绝对布局下的行隐藏:
     * 1) 行带 = 与文本垂直中心重叠的行级兄弟中最贴合的 (优先可点击的触摸层);
     * 2) 折叠行带内全部行级兄弟 (图标/文本/箭头/背景/触摸层);
     * 3) 绝对布局不会因 GONE 自动补位 —— 行带以下的兄弟整体上移, 消除空行。
     */
    private static void flatHideRow(View textView, View root, String text, int rowMaxH, int flatMaxH, int[] stats) {
        ViewParent tp = textView.getParent();
        if (!(tp instanceof ViewGroup)) {
            if (collapsedRows.add(textView)) {
                DebugLogger.i(TAG, "hide sidebar text(leaf): " + text);
                stats[1]++;
            }
            collapseView(textView);
            return;
        }
        ViewGroup parent = (ViewGroup) tp;
        int centerY = textView.getTop() + textView.getHeight() / 2;

        View rowRef = null;
        View bgRef = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == textView || child == root) continue;
            int ch = child.getHeight();
            if (ch <= 0 || ch > flatMaxH) continue;
            if (child.getTop() <= centerY && child.getBottom() >= centerY) {
                if (isClickableView(child)) {
                    rowRef = child; // 整行的触摸层
                    break;
                }
                int diff = Math.abs(ch - Math.max(textView.getHeight(), 40));
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bgRef = child;
                }
            }
        }
        if (rowRef == null) rowRef = bgRef;

        int bandTop, bandBottom;
        if (rowRef != null) {
            bandTop = rowRef.getTop();
            bandBottom = rowRef.getBottom();
        } else {
            bandTop = textView.getTop();
            bandBottom = textView.getBottom();
        }
        int bandH = Math.max(bandBottom - bandTop, textView.getHeight());

        int hidden = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == root) continue;
            int ch = child.getHeight();
            if (ch <= 0 || ch > flatMaxH) continue;
            if (child.getBottom() > bandTop && child.getTop() < bandBottom) {
                collapseView(child);
                hidden++;
            }
        }

        // 空行补位统一交给扫描末尾的 reflowVoids

        if (collapsedRows.add(textView)) {
            DebugLogger.i(TAG, "hide sidebar flat(ref=" + (rowRef != null ? rowRef.getClass().getSimpleName() : "text")
                    + " band=" + bandH + " hidden=" + hidden + "): " + text);
            stats[1]++;
        }
    }

    /**
     * 空行补位 (圆润化核心): 在"不会因 GONE 自动重排"的绝对布局容器 (RN/FrameLayout) 中,
     * 折叠的行仍保留原有空间形成空洞。对每个容器:
     * 1) 找出本 hook 折叠过的子视图 (sHiddenHeight 记录其原始高度), 按原始区间累计;
     * 2) 每个可见子视图按"其上方的全部隐藏行高度之和"整体上移 (可叠加, 多行一次补到位);
     * 3) 容器自身若为固定高度且高于可见内容, 收缩到刚好包住可见内容 + paddingBottom
     *    (绝对值目标 → 多轮扫描幂等, 不会越缩越小)。
     * 幂等保证: 每轮扫描开头 restoreShifts() 还原位移后重新计算。
     */
    private static int reflowVoids(View view, View root, int depth) {
        if (view == null || depth > 30 || view.getVisibility() != View.VISIBLE) return 0;
        int moved = 0;
        try {
            if (view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                if (!autoReflow(g) && g.getChildCount() > 1) {
                    boolean hasHidden = false;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c.getVisibility() == View.GONE && sHiddenHeight.containsKey(c)) {
                            hasHidden = true;
                            break;
                        }
                    }
                    if (hasHidden) {
                        // 折叠行的命中框必须一起挪走: RN 每次布局更新都会按 JS 树重新强加子视图坐标框,
                        // GONE 的行槽位仍在, 且手势系统 (react-native-gesture-handler) 按视图坐标命中,
                        // 导致"从空位点击仍打开条目"。把折叠行 translationY 移出屏幕后,
                        // 其命中框远离原槽位, 空位点击不再命中 (位移不属于 frame, RN 重渲染不会重置)。
                        for (int i = 0; i < g.getChildCount(); i++) {
                            View h = g.getChildAt(i);
                            if (h.getVisibility() == View.GONE && sHiddenHeight.containsKey(h)
                                    && h.getTranslationY() != HIDDEN_TRANSLATION) {
                                h.setTranslationY(HIDDEN_TRANSLATION);
                            }
                        }
                        // 孤儿分隔线: 紧挨隐藏行的细条 (非文本, 高度≤90px) 一并折叠,
                        // 消除隐藏行旁边残留的细缝 (层级 dump 实锤: 行间 49px 分隔线)
                        for (int i = 0; i < g.getChildCount(); i++) {
                            View c = g.getChildAt(i);
                            if (c.getVisibility() != View.VISIBLE || c instanceof TextView) continue;
                            int ch = c.getHeight();
                            if (ch <= 0 || ch > 90) continue;
                            boolean adjacentHidden =
                                    (i > 0 && isHiddenRow(g.getChildAt(i - 1)))
                                            || (i < g.getChildCount() - 1 && isHiddenRow(g.getChildAt(i + 1)));
                            if (adjacentHidden) collapseView(c);
                        }
                        for (int i = 0; i < g.getChildCount(); i++) {
                            View c = g.getChildAt(i);
                            if (c.getVisibility() != View.VISIBLE) continue;
                            int offset = 0;
                            for (int j = 0; j < g.getChildCount(); j++) {
                                View h = g.getChildAt(j);
                                if (h.getVisibility() != View.GONE || !sHiddenHeight.containsKey(h)) continue;
                                Integer hh = sHiddenHeight.get(h);
                                if (hh == null) continue;
                                // 只累计"完整位于本行上方"的隐藏行, 避免错误覆盖
                                if (h.getBottom() <= c.getTop()) offset += hh;
                            }
                            if (offset > 0) {
                                shiftBy(c, -offset);
                                moved++;
                            }
                        }
                        // 固定高度容器收缩 (lp.height>0; RN 视图 lp.height=0 不走此分支,
                        // RN 内容高度由 Yoga 直接 layout() 施加, 改 lp 会与之冲突)
                        ViewGroup.LayoutParams lp = g.getLayoutParams();
                        if (lp != null && lp.height > 0) {
                            int h = g.getHeight();
                            int desired = measureContentBottom(g) + g.getPaddingBottom();
                            if (h > 0 && desired > 0 && desired < h) {
                                lp.height = desired;
                                g.setLayoutParams(lp);
                                DebugLogger.i(TAG, "shrink " + g.getClass().getSimpleName()
                                        + ": h " + h + " -> " + desired);
                            }
                        }
                    }
                }
                // 滚动闸门 (每轮无条件评估, 不依赖本容器是否有隐藏行):
                // 按"未隐藏条目的实际内容高度"与滚动容器视口比较 ——
                // 超过屏幕才开启滚动, 没超过就锁滚动 (拖不动也无过滚动手感)
                if (g != root && isScrollContentContainer(g)) {
                    updateScrollGate(g);
                }
                for (int i = 0; i < g.getChildCount(); i++) {
                    moved += reflowVoids(g.getChildAt(i), root, depth + 1);
                }
            }
        } catch (Throwable t) {
            DebugLogger.d(TAG, "reflowVoids error: " + t);
        }
        return moved;
    }

    /** 内容容器判定: 父容器是滚动容器 (ScrollView/RecyclerView) */
    private static boolean isScrollContentContainer(ViewGroup g) {
        ViewParent pp = g.getParent();
        if (!(pp instanceof View)) return false;
        String pn = ((View) pp).getClass().getName();
        return pn.contains("ScrollView") || pn.contains("RecyclerView");
    }

    /** 可见子视图的渲染底部 (frame + translation), 即压缩后内容的实际底边 */
    private static int measureContentBottom(ViewGroup g) {
        int contentBottom = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            int cb = c.getBottom() + (int) c.getTranslationY();
            if (cb > contentBottom) contentBottom = cb;
        }
        return contentBottom;
    }

    /**
     * 滚动闸门: 未隐藏条目内容高度 > 滚动容器视口 → 开启滚动;
     * 否则关闭滚动 (RN ReactScrollView.setScrollEnabled, 反射按形状查找) 并归零滚动位置。
     */
    private static void updateScrollGate(ViewGroup content) {
        try {
            ViewParent svp = content.getParent();
            if (!(svp instanceof View)) return;
            View sv = (View) svp;
            int contentBottom = measureContentBottom(content);
            if (contentBottom <= 0) return;
            int viewportH = sv.getHeight();
            boolean needScroll = contentBottom > viewportH;
            java.lang.reflect.Method se = findSetScrollEnabled(sv.getClass());
            if (se != null) {
                try {
                    se.invoke(sv, needScroll);
                } catch (Throwable ignored) {
                }
            }
            if (!sGatedScrollViews.containsKey(sv)) {
                sGatedScrollViews.put(sv, sv.getOverScrollMode());
            }
            Integer orig = sGatedScrollViews.get(sv);
            sv.setOverScrollMode(needScroll
                    ? (orig != null ? orig : View.OVER_SCROLL_ALWAYS)
                    : View.OVER_SCROLL_NEVER);
            if (!needScroll && sv.getScrollY() != 0) {
                sv.scrollTo(0, 0);
            }
            DebugLogger.d(TAG, "scroll gate: content=" + contentBottom
                    + " viewport=" + viewportH + " enabled=" + needScroll);
        } catch (Throwable t) {
            DebugLogger.d(TAG, "updateScrollGate error: " + t);
        }
    }

    /** 是否为本 hook 折叠过的隐藏行 (孤儿分隔线判定用) */
    private static boolean isHiddenRow(View v) {
        return v != null && v.getVisibility() == View.GONE && sHiddenHeight.containsKey(v);
    }

    /**
     * 沿类层次查找 setScrollEnabled(boolean) (RN ReactScrollView 的公共滚动开关)。
     * 按方法形状反射查找, 不硬编码混淆名; 找不到返回 null (降级为仅禁过滚动)。
     */
    private static java.lang.reflect.Method findSetScrollEnabled(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getMethod("setScrollEnabled", boolean.class);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean sDumped = false;

    /**
     * 一次性层级诊断 dump (首个抽屉打开时输出一次, 限深限节点数):
     * 每行: 缩进 + 类名 + 可见性 + [top,bottom] + lp.height + 文本。
     * 用于精确确认抽屉的实际视图结构与行容器边界。
     */
    private static void dumpTreeOnce(View root) {
        if (sDumped || root == null) return;
        sDumped = true;
        try {
            StringBuilder sb = new StringBuilder("hierarchy:\n");
            int[] budget = new int[]{80};
            dumpNode(root, 0, sb, budget);
            DebugLogger.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void dumpNode(View v, int depth, StringBuilder sb, int[] budget) {
        if (v == null || depth > 14 || budget[0]-- <= 0) return;
        String cls = v.getClass().getSimpleName();
        if (cls.isEmpty()) cls = v.getClass().getName();
        String className = v.getClass().getName();
        if (className.contains("RecyclerView") || className.contains("ScrollView")) {
            cls = className.substring(className.lastIndexOf('.') + 1);
        }
        String text = "";
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.length() > 0) {
                String s = cs.toString().trim();
                if (s.length() > 10) s = s.substring(0, 10) + "…";
                text = " \"" + s + "\"";
            }
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int lpH = lp != null ? lp.height : -99;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(cls).append(v.getVisibility() == View.VISIBLE ? 'V' : (v.getVisibility() == View.GONE ? 'G' : 'I'))
                .append('[').append(v.getTop()).append(',').append(v.getBottom()).append(']')
                .append(" lp=").append(lpH).append(text).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpNode(g.getChildAt(i), depth + 1, sb, budget);
            }
        }
    }

    /** 记录被位移前的原始 translationY (幂等位移用: 每轮扫描先还原再重算) */
    private static final java.util.WeakHashMap<View, Float> sBaseTranslation = new java.util.WeakHashMap<>();

    /** 折叠行的命中框移出屏幕的距离 (手势系统按视图坐标命中, 槽位必须一起挪走) */
    private static final float HIDDEN_TRANSLATION = -5000f;

    /**
     * 施加位移。必须在同一轮内可叠加 (多个隐藏行上方的行需要累计位移),
     * 幂等性由"每轮扫描开头 restoreAll() 还原到原始位置"保证。
     */
    private static void shiftBy(View v, float delta) {
        if (v == null) return;
        if (!sBaseTranslation.containsKey(v)) {
            sBaseTranslation.put(v, v.getTranslationY());
        }
        v.setTranslationY(v.getTranslationY() + delta);
    }

    /** 被本 hook 关闭过滚动的容器 -> 原始 overScrollMode (功能关闭时还原) */
    private static final java.util.WeakHashMap<View, Integer> sGatedScrollViews = new java.util.WeakHashMap<>();

    /**
     * 整体还原: 把此前折叠的行、施加的位移、关闭的滚动开关全部恢复到原始状态。
     * 每轮扫描开头调用 —— 先还原再重新隐藏, 这样取消勾选的条目能恢复显示,
     * 功能关闭时抽屉回到完全原状 (包括滚动能力)。
     */
    private static void restoreAll(View root) {
        try {
            if (sBaseTranslation.isEmpty() && sHiddenHeight.isEmpty()
                    && sGatedScrollViews.isEmpty() && collapsedRows.isEmpty()) {
                return; // 无任何修改过状态, 快速返回
            }
            for (java.util.Map.Entry<View, Float> e : sBaseTranslation.entrySet()) {
                View v = e.getKey();
                Float base = e.getValue();
                if (v != null && base != null) v.setTranslationY(base);
            }
            sBaseTranslation.clear();
            restoreNode(root, 0);
            sHiddenHeight.clear();
            collapsedRows.clear();
            for (java.util.Map.Entry<View, Integer> e : sGatedScrollViews.entrySet()) {
                View sv = e.getKey();
                Integer mode = e.getValue();
                if (sv == null) continue;
                java.lang.reflect.Method se = findSetScrollEnabled(sv.getClass());
                if (se != null) {
                    try {
                        se.invoke(sv, true);
                    } catch (Throwable ignored) {
                    }
                }
                if (mode != null) sv.setOverScrollMode(mode);
            }
            sGatedScrollViews.clear();
            DebugLogger.d(TAG, "restoreAll: collapsed rows/scroll states reset");
        } catch (Throwable t) {
            DebugLogger.d(TAG, "restoreAll error: " + t);
        }
    }

    /** 递归还原所有被折叠视图的可见性/尺寸/margin/位移 */
    private static void restoreNode(View v, int depth) {
        if (v == null || depth > 30) return;
        if (sHiddenHeight.containsKey(v)) {
            int[] orig = sOriginalState.get(v);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && orig != null && orig.length >= 6) {
                lp.height = orig[0];
                lp.width = orig[1];
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).setMargins(orig[2], orig[3], orig[4], orig[5]);
                }
                v.setLayoutParams(lp);
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
            v.setClickable(true);
            v.setEnabled(true);
            if (v.getTranslationY() != 0f) v.setTranslationY(0f);
            v.requestLayout();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                restoreNode(g.getChildAt(i), depth + 1);
            }
        }
    }

    /** 被折叠视图的原始高度 (补位计算用; 折叠前捕获) */
    private static final java.util.WeakHashMap<View, Integer> sHiddenHeight = new java.util.WeakHashMap<>();

    private static void collapseView(View view) {
        if (view == null) return;
        // 记录折叠前的原始高度: 补位时按此高度累计, 与折叠后 height=0 无关
        try {
            int h = view.getHeight();
            if (h <= 0) {
                ViewGroup.LayoutParams lp0 = view.getLayoutParams();
                if (lp0 != null && lp0.height > 0) h = lp0.height;
            }
            if (h > 0 && !sHiddenHeight.containsKey(view)) {
                sHiddenHeight.put(view, h);
            }
        } catch (Throwable ignored) {
        }
        if (view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
        }
        view.setClickable(false);
        view.setEnabled(false);
        // 命中框一起移出屏幕: RN 会按 JS 树重新强加坐标框 (GONE 的行槽位仍在),
        // 手势系统按视图坐标命中, 不挪走命中框就会"从空位点击打开已隐藏条目"
        if (view.getTranslationY() != HIDDEN_TRANSLATION) {
            view.setTranslationY(HIDDEN_TRANSLATION);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        // 记录原始尺寸/margin 供还原 (restoreAll 整体还原时按此恢复)
        if (lp != null && !sOriginalState.containsKey(view)) {
            int mL = 0, mT = 0, mR = 0, mB = 0;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mL = mlp.leftMargin;
                mT = mlp.topMargin;
                mR = mlp.rightMargin;
                mB = mlp.bottomMargin;
            }
            sOriginalState.put(view, new int[]{lp.height, lp.width, mL, mT, mR, mB});
        }
        if (lp != null && (lp.height != 0 || lp.width != 0)) {
            lp.height = 0;
            lp.width = 0;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
            }
            view.setLayoutParams(lp);
        }
    }

    /**
     * 行文本是否命中已勾选的隐藏条目 (键空间统一为条目名, 纯精确匹配)。
     */
    private static boolean shouldHideSidebarText(String rowText, HashMap<String, Boolean> settingMap) {
        if (rowText == null) return false;
        return Boolean.TRUE.equals(settingMap.get(rowText.trim()));
    }
}
