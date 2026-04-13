package ai.nex.interaction.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;

import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.robotchat.facedet.model.BodyResult;
import com.robotchat.facedet.model.BoundingBox;
import com.robotchat.facedet.model.FaceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调试可视化覆盖层：在相机预览上叠加绘制检测结果。
 *
 * <p>渲染内容：
 * <ul>
 *   <li>人脸 bbox（彩色矩形，按 identity 分配颜色）</li>
 *   <li>人体 bbox（虚线矩形，颜色与关联 face identity 一致）</li>
 *   <li>身份标签（globalFaceId、bodyId、registered 状态）</li>
 *   <li>面部关键点（478 个归一化点）</li>
 *   <li>眼睛朝向箭头（基于 gaze 数据）</li>
 *   <li>头部姿态三轴指示器</li>
 *   <li>嘴唇关键点（高亮）</li>
 *   <li>人体骨架（33 个关键点连线）</li>
 * </ul>
 *
 * <p>坐标变换：分析帧 → 旋转 → 前置镜像 → FILL_CENTER 缩放至 PreviewView 显示区域。
 */
public class DebugOverlayView extends View {

    // ── 数据输入 ────────────────────────────────────────────────────

    private final List<FaceResult> faceResults = new ArrayList<>();
    private final List<BodyResult> bodyResults = new ArrayList<>();

    /** faceId → float[478][2] (归一化 x, y，MediaPipe 原始坐标系) */
    private final Map<Integer, float[][]> faceLandmarksById = new HashMap<>();

    /** faceId → MediaPipe 坐标系相对预览的旋转角度（度） */
    private final Map<Integer, Integer> faceRotationById = new HashMap<>();

    /** 分析帧尺寸（由首帧 Bitmap 实际尺寸自动同步，默认 480x640 作为 fallback） */
    private int analysisW = 480;
    private int analysisH = 640;

    /** 相机传感器 → 显示方向的旋转角度（0/90/180/270） */
    private int imageRotationDegrees = 0;

    /**
     * 竖屏手机上前置 PreviewView 与 ImageAnalysis 映射有时在 Y 轴与手写旋转公式不一致，
     * 表现为「人在画面下方、框画在上方」。为 true 时在映射到 View 坐标时对 Y 做翻转。
     */
    private boolean invertPreviewY = false;

    /** 是否启用（可由配置控制） */
    private boolean enabled = true;

    /** @param invert 为 true 时在 toView 中对 Y 做 1−y 翻转，用于竖屏前置与 Preview 对齐 */
    public void setInvertPreviewY(boolean invert) {
        if (this.invertPreviewY == invert) {
            return;
        }
        this.invertPreviewY = invert;
        invalidate();
    }

    // ── 坐标变换 ────────────────────────────────────────────────────

    /** PreviewView 显示区域 */
    private RectF viewRect = new RectF();

    public void setAnalysisSize(int w, int h) {
        this.analysisW = w;
        this.analysisH = h;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeViewRect();
    }

    private void computeViewRect() {
        if (analysisW <= 0 || analysisH <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            viewRect.set(0, 0, getWidth(), getHeight());
            return;
        }
        float viewW = getWidth();
        float viewH = getHeight();

        // 旋转后的分析帧逻辑尺寸（用于宽高比计算）
        int dispW = analysisW;
        int dispH = analysisH;
        if (imageRotationDegrees == 90 || imageRotationDegrees == 270) {
            dispW = analysisH;
            dispH = analysisW;
        }

        float frameAspect = (float) dispW / dispH;
        float viewAspect = viewW / viewH;

        // FILL_CENTER: 放大到填满视图（与 PreviewView 默认行为一致）
        if (frameAspect > viewAspect) {
            // 帧更宽 → 按高度填满，宽度溢出
            float w = viewH * frameAspect;
            viewRect.set(0, 0, w, viewH);
        } else {
            // 帧更高 → 按宽度填满，高度溢出
            float h = viewW / frameAspect;
            viewRect.set(0, 0, viewW, h);
        }
        float offsetX = (viewW - viewRect.width()) / 2;
        float offsetY = (viewH - viewRect.height()) / 2;
        viewRect.offset(offsetX, offsetY);
    }

    /** 复用数组，避免 onDraw 中频繁分配 */
    private final float[] viewXY = new float[2];

    /**
     * 将分析帧像素坐标 (x, y) 转换为 View 坐标（含旋转 + 前置镜像 + FILL_CENTER 缩放）。
     *
     * @return 内部复用数组 [viewX, viewY]，调用者须在下一次 toView 前提取值
     */
    private float[] toView(float x, float y) {
        float rx, ry, dw, dh;
        switch (imageRotationDegrees) {
            case 90:
                rx = y;
                ry = (float) analysisW - x;
                dw = analysisH;
                dh = analysisW;
                break;
            case 180:
                rx = (float) analysisW - x;
                ry = (float) analysisH - y;
                dw = analysisW;
                dh = analysisH;
                break;
            case 270:
                rx = (float) analysisH - y;
                ry = x;
                dw = analysisH;
                dh = analysisW;
                break;
            default:
                rx = x;
                ry = y;
                dw = analysisW;
                dh = analysisH;
                break;
        }
        rx = dw - rx; // front camera horizontal mirror
        viewXY[0] = viewRect.left + rx * (viewRect.width() / dw);
        float yNorm = ry / dh;
        if (invertPreviewY) {
            yNorm = 1f - yNorm;
        }
        viewXY[1] = viewRect.top + yNorm * viewRect.height();
        return viewXY;
    }

    // ── 身份颜色映射 ─────────────────────────────────────────────────

    private final Map<String, Integer> identityColors = new HashMap<>();
    private int nextColorIndex = 0;

    private static final int[] PALETTE = new int[] {
        Color.parseColor("#00E676"), // 绿
        Color.parseColor("#448AFF"), // 蓝
        Color.parseColor("#FFD600"), // 黄
        Color.parseColor("#FF6D00"), // 橙
        Color.parseColor("#D500F9"), // 紫
        Color.parseColor("#00B8D4"), // 青
        Color.parseColor("#FF1744"), // 红
        Color.parseColor("#69F0AE"), // 浅绿
        Color.parseColor("#B388FF"), // 浅紫
        Color.parseColor("#FFFF00"), // 亮黄
    };

    private int getColorForIdentity(String identity) {
        if (identity == null) {
            return Color.parseColor("#9E9E9E"); // 灰
        }
        if (identity.startsWith("unknown") || identity.startsWith("temp_")) {
            return Color.parseColor("#BDBDBD"); // 浅灰
        }
        if (!identityColors.containsKey(identity)) {
            identityColors.put(identity, PALETTE[nextColorIndex % PALETTE.length]);
            nextColorIndex++;
        }
        return identityColors.get(identity);
    }

    // ── Paint 复用 ───────────────────────────────────────────────────

    private final Paint faceBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint landmarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gazeArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodySkeletonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyLandmarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DebugOverlayView(Context context) {
        super(context);
        init();
    }

    public DebugOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DebugOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        faceBoxPaint.setStyle(Paint.Style.STROKE);
        faceBoxPaint.setStrokeWidth(3f);

        bodyBoxPaint.setStyle(Paint.Style.STROKE);
        bodyBoxPaint.setStrokeWidth(2f);
        bodyBoxPaint.setPathEffect(new DashPathEffect(new float[]{10, 5}, 0));

        labelPaint.setTextSize(24f);
        labelPaint.setColor(Color.WHITE);

        landmarkPaint.setStyle(Paint.Style.FILL);
        landmarkPaint.setColor(Color.parseColor("#AA2196F3"));

        lipPaint.setStyle(Paint.Style.FILL);
        lipPaint.setColor(Color.parseColor("#FFFF5722"));

        eyePaint.setStyle(Paint.Style.FILL);
        eyePaint.setColor(Color.parseColor("#FF4CAF50"));

        gazeArrowPaint.setStyle(Paint.Style.STROKE);
        gazeArrowPaint.setStrokeWidth(2f);
        gazeArrowPaint.setColor(Color.parseColor("#FF00E676"));

        headAxisPaint.setStyle(Paint.Style.STROKE);
        headAxisPaint.setStrokeWidth(3f);

        bodySkeletonPaint.setStyle(Paint.Style.STROKE);
        bodySkeletonPaint.setStrokeWidth(3f);

        bodyLandmarkPaint.setStyle(Paint.Style.FILL);
        bodyLandmarkPaint.setColor(Color.parseColor("#FF8BC34A"));
    }

    // ── 数据输入 ────────────────────────────────────────────────────

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        invalidate();
    }

    public void updateFaces(List<FaceResult> faces) {
        List<FaceResult> snapshot;
        synchronized (faceResults) {
            faceResults.clear();
            if (faces != null) {
                faceResults.addAll(faces);
            }
            snapshot = new ArrayList<>(faceResults);
        }
        synchronized (faceLandmarksById) {
            for (FaceResult fr : snapshot) {
                faceRotationById.put(fr.faceId, fr.faceRotationDegrees);
            }
        }
        invalidate();
    }

    public void updateBodies(List<BodyResult> bodies) {
        synchronized (bodyResults) {
            bodyResults.clear();
            if (bodies != null) {
                bodyResults.addAll(bodies);
            }
        }
        invalidate();
    }

    /**
     * 设置原始人脸关键点（478 点归一化坐标，分析帧坐标系）。
     *
     * <p>归一化坐标直接乘以 frameW/frameH 转为像素坐标，旋转由 {@link #toView} 统一处理。
     *
     * @param landmarks float[478][2] 归一化坐标
     * @param rotationDegrees 相机传感器 → 显示方向的旋转角度（0/90/180/270）
     */
    public void setFaceLandmarks(int faceId, float[][] landmarks, int frameW, int frameH, int rotationDegrees) {
        if (landmarks == null) return;
        boolean sizeChanged = frameW > 0 && frameH > 0
                && (frameW != analysisW || frameH != analysisH);
        boolean rotChanged = rotationDegrees != imageRotationDegrees;
        if (sizeChanged) {
            setAnalysisSize(frameW, frameH);
        }
        if (rotChanged) {
            imageRotationDegrees = rotationDegrees;
        }
        if (sizeChanged || rotChanged) {
            computeViewRect();
        }
        float[][] px = new float[landmarks.length][2];
        for (int i = 0; i < landmarks.length; i++) {
            px[i][0] = landmarks[i][0] * frameW;
            px[i][1] = landmarks[i][1] * frameH;
        }
        synchronized (faceLandmarksById) {
            faceLandmarksById.put(faceId, px);
            // rotationDegrees 为相机→屏幕旋转，勿写入 faceRotationById（该 map 存 MediaPipe 人脸朝向，由 updateFaces 维护）
        }
    }

    /**
     * 移除不在活跃列表中的 landmark / rotation 缓存，避免残留。
     */
    public void pruneStale(java.util.Set<Integer> activeFaceIds) {
        synchronized (faceLandmarksById) {
            faceLandmarksById.keySet().retainAll(activeFaceIds);
            faceRotationById.keySet().retainAll(activeFaceIds);
        }
    }

    public void clear() {
        synchronized (faceResults) { faceResults.clear(); }
        synchronized (bodyResults) { bodyResults.clear(); }
        synchronized (faceLandmarksById) { faceLandmarksById.clear(); }
        synchronized (faceRotationById) { faceRotationById.clear(); }
        invalidate();
    }

    // ── 绘制 ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!enabled) return;
        if (viewRect.width() <= 0 || viewRect.height() <= 0) {
            computeViewRect();
            if (viewRect.width() <= 0) return;
        }

        // 深色半透明背景
        canvas.drawColor(Color.argb(40, 0, 0, 0));

        List<BodyResult> bodies;
        synchronized (bodyResults) {
            bodies = new ArrayList<>(bodyResults);
        }

        List<FaceResult> faces;
        synchronized (faceResults) {
            faces = new ArrayList<>(faceResults);
        }

        Map<Integer, float[][]> landmarksMap;
        synchronized (faceLandmarksById) {
            landmarksMap = new HashMap<>(faceLandmarksById);
        }

        // 绘制人体骨架（先画，face bbox 盖在上面）
        for (BodyResult body : bodies) {
            drawBodySkeleton(canvas, body);
        }

        // 绘制人体 bbox
        for (BodyResult body : bodies) {
            drawBodyBbox(canvas, body);
        }

        // 绘制人脸相关
        for (FaceResult face : faces) {
            drawFaceBbox(canvas, face);
            drawFaceLandmarks(canvas, face, landmarksMap.get(face.faceId));
            drawHeadPoseAxis(canvas, face);
            drawGazeArrows(canvas, face, landmarksMap.get(face.faceId));
            drawIdentityLabel(canvas, face);
        }
    }

    // ── 人体骨架绘制 ─────────────────────────────────────────────────

    /** MediaPipe Pose 标准连接对 (from, to, visibility threshold) */
    private static final int[][] POSE_SKELETON = new int[][] {
        // 躯干
        {11, 12},   // 左肩-右肩
        {11, 23},   // 左肩-左髋
        {12, 24},   // 右肩-右髋
        {23, 24},   // 左髋-右髋
        // 左臂
        {11, 13},   // 左肩-左肘
        {13, 15},   // 左肘-左手腕
        // 右臂
        {12, 14},   // 右肩-右肘
        {14, 16},   // 右肘-右手腕
        // 左腿
        {23, 25},   // 左髋-左膝
        {25, 27},   // 左膝-左踝
        // 右腿
        {24, 26},   // 右髋-右膝
        {26, 28},   // 右膝-右踝
        // 面部（简化）
        {0, 1},     // 鼻-额头
        {1, 2},     // 额-左眼
        {1, 5},     // 额-右眼
        {2, 4},     // 左眼-左耳
        {5, 7},     // 右眼-右耳
        {9, 10},    // 左嘴-右嘴
    };

    /** 每个连接的颜色（可自定义） */
    private void drawBodySkeleton(Canvas canvas, BodyResult body) {
        if (body.landmarks == null) return;
        float[][] lm = body.landmarks;
        if (lm.length < 33) return;

        // 找 x, y 的范围用于归一化（world coords → 相对偏移）
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 33; i++) {
            float x = lm[i][0], y = lm[i][1];
            if (Float.isNaN(x) || Float.isNaN(y)) continue;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        float rangeX = maxX - minX;
        float rangeY = maxY - minY;
        if (rangeX < 1e-5f) rangeX = 1;
        if (rangeY < 1e-5f) rangeY = 1;

        String identity = resolveBodyIdentity(body.bodyId);
        int color = getColorForIdentity(identity);
        bodySkeletonPaint.setColor(color);

        // body bbox 中心作为骨架偏移基准
        float cx = body.bbox.centerX();
        float cy = body.bbox.centerY();
        float scale = Math.min(body.bbox.width() / rangeX, body.bbox.height() / rangeY) * 0.8f;

        float midX = (minX + maxX) / 2;
        float midY = (minY + maxY) / 2;

        for (int[] conn : POSE_SKELETON) {
            int a = conn[0], b = conn[1];
            if (a >= lm.length || b >= lm.length) continue;
            float ax = lm[a][0], ay = lm[a][1];
            float bx = lm[b][0], by = lm[b][1];
            if (Float.isNaN(ax) || Float.isNaN(ay) || Float.isNaN(bx) || Float.isNaN(by)) continue;
            float[] v1 = toView(cx + (ax - midX) * scale, cy + (ay - midY) * scale);
            float v1x = v1[0], v1y = v1[1];
            float[] v2 = toView(cx + (bx - midX) * scale, cy + (by - midY) * scale);
            canvas.drawLine(v1x, v1y, v2[0], v2[1], bodySkeletonPaint);
        }

        for (int i = 0; i < 33; i++) {
            if (i >= lm.length) break;
            float x = lm[i][0], y = lm[i][1];
            if (Float.isNaN(x) || Float.isNaN(y)) continue;
            float[] v = toView(cx + (x - midX) * scale, cy + (y - midY) * scale);
            float radius = (i == 0 || i == 11 || i == 12 || i == 23 || i == 24) ? 6f : 4f;
            canvas.drawCircle(v[0], v[1], radius, bodyLandmarkPaint);
        }
    }

    private void drawBodyBbox(Canvas canvas, BodyResult body) {
        String identity = resolveBodyIdentity(body.bodyId);
        int color = getColorForIdentity(identity);
        bodyBoxPaint.setColor(color);

        float[] v1 = toView(body.bbox.x1, body.bbox.y1);
        float vx1 = v1[0], vy1 = v1[1];
        float[] v2 = toView(body.bbox.x2, body.bbox.y2);
        float vx2 = v2[0], vy2 = v2[1];
        canvas.drawRect(Math.min(vx1, vx2), Math.min(vy1, vy2),
                        Math.max(vx1, vx2), Math.max(vy1, vy2), bodyBoxPaint);

        labelPaint.setTextSize(20f);
        labelPaint.setColor(color);
        String label = "body:" + body.bodyId + " " + (identity != null ? identity : "unknown");
        canvas.drawText(label, Math.min(vx1, vx2), Math.min(vy1, vy2) - 4, labelPaint);
    }

    // ── 人脸绘制 ────────────────────────────────────────────────────

    private void drawFaceBbox(Canvas canvas, FaceResult face) {
        int color = getColorForIdentity(face.globalFaceId);
        faceBoxPaint.setColor(color);
        faceBoxPaint.setStrokeWidth(face.identityRegistered ? 4f : 3f);

        float[] v1 = toView(face.bbox.x1, face.bbox.y1);
        float vx1 = v1[0], vy1 = v1[1];
        float[] v2 = toView(face.bbox.x2, face.bbox.y2);
        float vx2 = v2[0], vy2 = v2[1];
        canvas.drawRect(Math.min(vx1, vx2), Math.min(vy1, vy2),
                        Math.max(vx1, vx2), Math.max(vy1, vy2), faceBoxPaint);
    }

    private void drawIdentityLabel(Canvas canvas, FaceResult face) {
        int color = getColorForIdentity(face.globalFaceId);
        labelPaint.setTextSize(22f);
        labelPaint.setColor(color);

        String line1 = face.globalFaceId != null ? face.globalFaceId : "unknown";
        if (face.identityRegistered) {
            line1 += " \u2713";
        }
        line1 += " faceId:" + face.faceId;

        float[] v1 = toView(face.bbox.x1, face.bbox.y1);
        float[] v2 = toView(face.bbox.x2, face.bbox.y2);
        float labelX = Math.min(v1[0], v2[0]);
        float labelY = Math.min(v1[1], v2[1]);
        canvas.drawText(line1, labelX, labelY - 6, labelPaint);

        labelPaint.setTextSize(16f);
        labelPaint.setColor(Color.argb(200, 255, 255, 255));
        String line2 = String.format("pose yaw:%.1f pitch:%.1f",
            face.headPose.yaw, face.headPose.pitch);
        canvas.drawText(line2, labelX, labelY - 28, labelPaint);

        String line3 = String.format("gaze yaw:%.1f pitch:%.1f",
            face.gaze.avgYaw, face.gaze.avgPitch);
        canvas.drawText(line3, labelX, labelY - 46, labelPaint);

        if (face.lip.isSpeaking()) {
            labelPaint.setColor(Color.parseColor("#FFFF5722"));
            canvas.drawText("SPEAKING", labelX, labelY - 64, labelPaint);
        }
    }

    private void drawFaceLandmarks(Canvas canvas, FaceResult face, float[][] landmarks) {
        if (landmarks == null || landmarks.length == 0) return;

        int[] keyIndices = {1, 33, 133, 362, 263, 13, 14, 78, 308};
        for (int idx : keyIndices) {
            if (idx >= landmarks.length) continue;
            float x = landmarks[idx][0];
            float y = landmarks[idx][1];
            if (Float.isNaN(x) || Float.isNaN(y)) continue;
            float[] v = toView(x, y);
            if (idx == 33 || idx == 133 || idx == 362 || idx == 263) {
                eyePaint.setColor(Color.parseColor("#FF4CAF50"));
                canvas.drawCircle(v[0], v[1], 4f, eyePaint);
            } else if (idx == 13 || idx == 14 || idx == 78 || idx == 308) {
                lipPaint.setColor(face.lip.isSpeaking()
                    ? Color.parseColor("#FFFF5722")
                    : Color.parseColor("#FFFFC107"));
                canvas.drawCircle(v[0], v[1], 4f, lipPaint);
            } else if (idx == 1) {
                landmarkPaint.setColor(Color.WHITE);
                canvas.drawCircle(v[0], v[1], 5f, landmarkPaint);
            }
        }

        landmarkPaint.setColor(Color.argb(100, 33, 150, 243));
        for (int i = 0; i < landmarks.length; i += 3) {
            float x = landmarks[i][0];
            float y = landmarks[i][1];
            if (Float.isNaN(x) || Float.isNaN(y)) continue;
            float[] v = toView(x, y);
            canvas.drawCircle(v[0], v[1], 2f, landmarkPaint);
        }
    }

    private void drawHeadPoseAxis(Canvas canvas, FaceResult face) {
        float[] vc = toView(face.bbox.centerX(), face.bbox.centerY());
        float cx = vc[0], cy = vc[1];

        float yaw = (float) Math.toRadians(face.headPose.yaw);
        float pitch = (float) Math.toRadians(face.headPose.pitch);
        float roll = (float) Math.toRadians(face.headPose.roll);
        float len = 20f;

        headAxisPaint.setColor(Color.parseColor("#FFFF1744"));
        float yawX = (float) Math.sin(yaw) * len;
        float yawZ = (float) Math.cos(yaw) * len;
        canvas.drawLine(cx, cy, cx + yawX, cy - yawZ, headAxisPaint);

        headAxisPaint.setColor(Color.parseColor("#FF00E676"));
        float pitchY = (float) Math.sin(pitch) * len;
        float pitchZ = (float) Math.cos(pitch) * len;
        canvas.drawLine(cx, cy, cx - pitchY, cy - pitchZ, headAxisPaint);

        headAxisPaint.setColor(Color.parseColor("#FF448AFF"));
        float rollX = (float) Math.sin(roll) * len;
        float rollY = (float) Math.cos(roll) * len;
        canvas.drawLine(cx, cy, cx + rollX, cy - rollY, headAxisPaint);
    }

    private void drawGazeArrows(Canvas canvas, FaceResult face, float[][] landmarks) {
        if (landmarks == null || landmarks.length < 474) return;

        int leftEyeCenterIdx = 468;
        int rightEyeCenterIdx = 473;

        if (leftEyeCenterIdx >= landmarks.length || rightEyeCenterIdx >= landmarks.length) return;

        int color = getColorForIdentity(face.globalFaceId);
        gazeArrowPaint.setColor(color);

        float lx = landmarks[leftEyeCenterIdx][0];
        float ly = landmarks[leftEyeCenterIdx][1];
        if (!Float.isNaN(lx) && !Float.isNaN(ly)) {
            float[] v = toView(lx, ly);
            float arrowLen = 25f;
            float ax = (float) Math.sin(Math.toRadians(face.gaze.leftYaw)) * arrowLen;
            float ay = (float) Math.sin(Math.toRadians(face.gaze.leftPitch)) * arrowLen;
            canvas.drawLine(v[0], v[1], v[0] + ax, v[1] + ay, gazeArrowPaint);
        }

        float rx = landmarks[rightEyeCenterIdx][0];
        float ry = landmarks[rightEyeCenterIdx][1];
        if (!Float.isNaN(rx) && !Float.isNaN(ry)) {
            float[] v = toView(rx, ry);
            float arrowLen = 25f;
            float ax = (float) Math.sin(Math.toRadians(face.gaze.rightYaw)) * arrowLen;
            float ay = (float) Math.sin(Math.toRadians(face.gaze.rightPitch)) * arrowLen;
            canvas.drawLine(v[0], v[1], v[0] + ax, v[1] + ay, gazeArrowPaint);
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /** 根据 bodyId 查找关联的 face identity */
    private String resolveBodyIdentity(int bodyId) {
        synchronized (faceResults) {
            for (FaceResult face : faceResults) {
                if (face.bodyId == bodyId) {
                    return face.globalFaceId;
                }
            }
        }
        return null;
    }
}
