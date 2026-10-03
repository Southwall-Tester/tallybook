package dev.tallybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, short-lived request for the user's USB-connected local companion. */
public final class QuerySyncActivity extends Activity {
    private static final int BG=Color.rgb(245,246,242), INK=Color.rgb(25,47,38),
            MUTED=Color.rgb(108,120,111), GREEN=Color.rgb(27,69,53), PALE=Color.rgb(229,237,225);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView state, detail;
    private Button start, cancel;
    private Spinner pages;
    private boolean resumed, working, confirming;
    private final Runnable tick=new Runnable() {
        @Override public void run() { poll(); if(resumed) main.postDelayed(this,1500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout shell=column(); shell.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            shell.setOnApplyWindowInsetsListener((v,insets)->{
                Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                v.setPadding(b.left,b.top,b.right,b.bottom); return insets;
            });
        }
        ScrollView scroll=new ScrollView(this);
        LinearLayout body=column(); body.setPadding(dp(22),dp(14),dp(22),dp(28));
        Button back=button("返回小账本",false); back.setOnClickListener(v->finish()); body.addView(back);
        gap(body,18); body.addView(text("微信接口同步",27,INK,true));
        gap(body,9); body.addView(text("普通手机通过 USB 连接电脑，读取微信已登录账单页的数据。无需 Root 或 Xposed。",15,MUTED,false));
        gap(body,18);
        LinearLayout current=card(); state=text("检查本机状态…",21,INK,true); detail=text("",14,MUTED,false);
        current.addView(state); gap(current,10); current.addView(detail); gap(current,16);
        current.addView(text("本次最多读取",13,MUTED,true));
        pages=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"20 条 · 1 页","40 条 · 2 页","100 条 · 5 页","200 条 · 10 页"});
        pages.setAdapter(adapter); pages.setSelection(saved==null?1:saved.getInt("pages",1)); current.addView(pages);
        gap(current,12); start=button("开始查询并打开微信",true); start.setOnClickListener(v->begin()); current.addView(start);
        gap(current,8); cancel=button("取消本次查询",false); cancel.setOnClickListener(v->cancel()); current.addView(cancel);
        body.addView(current); gap(body,16);
        LinearLayout help=card(); help.addView(text("这次怎样同步",18,INK,true)); gap(help,10);
        help.addView(text("1. 手机用 USB 连着已授权的电脑。\n2. 电脑双击项目中的「微信接口同步.cmd」。\n3. 第一次使用，在微信内打开下面的调试地址，按微信提示允许。\n4. 点上方开始查询，进入微信 → 我 → 服务 → 钱包 → 账单并停留。\n5. 电脑完成查询后会打开小账本，显示新增、重复和跳过的数量。",14,MUTED,false));
        gap(help,12); TextView address=text("https://debugxweb.qq.com/?inspector=true",13,GREEN,false); address.setTextIsSelectable(true); help.addView(address);
        gap(help,12); help.addView(text("只有手机 APK、不运行电脑助手时，暂时不能独立同步。请求 5 分钟后失效；查询最多取所选页数，不代表全部历史账单。",13,MUTED,false));
        body.addView(help); gap(body,16);
        LinearLayout review=card(); review.addView(text("同步后先核对",18,INK,true)); gap(review,10);
        review.addView(text("记录先进入真实账本的待核对列表。转账、退款和自己账户之间的资金移动请特别检查，确认后才计入收支。没有明确收支方向的记录会跳过；同一来源的重复查询不会覆盖已核对结果。",14,MUTED,false));
        gap(review,14); Button ledger=button("查看同步账单",true); ledger.setOnClickListener(v->{setResult(RESULT_OK);finish();}); review.addView(ledger);
        gap(review,12); review.addView(text("账单只传到你连接的电脑与本机。结束后可在微信打开 https://debugxweb.qq.com/?inspector=false 关闭调试。",12,MUTED,false));
        body.addView(review); scroll.addView(body); shell.addView(scroll); setContentView(shell);
    }

    @Override protected void onResume(){super.onResume();resumed=true;main.post(tick);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("pages",pages.getSelectedItemPosition());super.onSaveInstanceState(state);}
    private boolean alive(){return !isFinishing()&&!isDestroyed();}

    private void poll(){
        if(working||confirming||io.isShutdown())return;
        working=true;
        io.execute(()->{
            QuerySyncStore.State status=null; String error=null;
            try{
                QuerySyncStore store=new QuerySyncStore(this);
                QuerySyncStore.ConsumeResult result=store.consumeInbox();
                if(result.consumed)getContentResolver().notifyChange(Uri.parse("content://dev.tallybook.app.capture/events"),null);
                status=store.state();
            }catch(RuntimeException unavailable){error="本机同步状态暂时无法读取，请重新打开后再试。";}
            QuerySyncStore.State value=status;String failure=error;
            main.post(()->{working=false;if(!alive())return;
                if(failure!=null){state.setText("暂时无法读取");detail.setText(failure);return;}
                state.setText(value.pending?"等待电脑查询":"本机同步状态");
                detail.setText(value.lastMessage+(value.pending?"\n剩余约 "+Math.max(0,(value.expiresAt-System.currentTimeMillis()+59999)/60000)+" 分钟。请停留在微信账单列表。":""));
                start.setEnabled(!value.pending);cancel.setEnabled(value.pending);pages.setEnabled(!value.pending);
            });
        });
    }

    private void begin(){
        if(working||confirming)return;
        confirming=true;
        AlertDialog confirmation=new AlertDialog.Builder(this).setTitle("查询自己的微信账单")
                .setMessage("本次查询由 USB 连接的电脑完成，结果保存到本机待核对账本。请先运行电脑助手，并在微信开启浏览器调试。")
                .setNegativeButton("取消",null).setPositiveButton("开始查询",(dialog,which)->{
                    working=true;start.setEnabled(false);
                    int[] choices={1,2,5,10};int selected=choices[pages.getSelectedItemPosition()];
                    io.execute(()->{String error=null;try{new QuerySyncStore(this).beginRequest(selected);}
                        catch(RuntimeException failed){error="查询请求未能保存，请检查本机空间后再试。";}
                        String failure=error;main.post(()->{working=false;if(!alive())return;
                            if(failure!=null)message(failure);else if(resumed)openWechat();poll();});});
                }).create();
        confirmation.setOnDismissListener(dialog->{confirming=false;if(resumed)poll();});
        confirmation.show();
    }
    private void cancel(){
        if(working)return;working=true;
        io.execute(()->{String error=null;try{new QuerySyncStore(this).cancelRequest();}
            catch(RuntimeException failed){error="暂时无法取消，请稍后再试。";}
            String failure=error;main.post(()->{working=false;if(!alive())return;if(failure!=null)message(failure);poll();});});
    }
    private void openWechat(){
        Intent intent=getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        try{if(intent==null)throw new ActivityNotFoundException();startActivity(intent);}
        catch(ActivityNotFoundException|SecurityException unavailable){message("请从桌面打开微信，再进入自己的账单列表。");}
    }
    private void message(String value){new AlertDialog.Builder(this).setMessage(value).setPositiveButton("知道了",null).show();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(18),dp(18),dp(18),dp(18));v.setBackground(shape(Color.WHITE,18));return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(4),1);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String label,boolean primary){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextSize(14);v.setTextColor(primary?Color.WHITE:GREEN);v.setBackground(shape(primary?GREEN:PALE,12));v.setMinHeight(dp(48));v.setPadding(dp(12),dp(9),dp(12),dp(9));v.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void gap(LinearLayout v,int height){v.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
