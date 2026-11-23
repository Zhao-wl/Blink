package com.zhaowl.blink;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

@CapacitorPlugin(name="BlinkCloud")
public class BlinkCloudPlugin extends Plugin {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private static final String ALIAS="blink-account-session-v1";
    private static final String RELEASE="https://github.com/Zhao-wl/Blink/releases/download/";
    private static final Set<String> ROUTES=new HashSet<>(Arrays.asList("v1/login","v1/register","v1/logout","v1/sync","v2/sync","v2/devices","v2/revoke","v2/reset","v2/password","v2/delete","v2/recovery-code"));
    private boolean stage(){return getContext().getPackageName().equals("com.zhaowl.blink.stage");}
    private SharedPreferences prefs(){return getContext().getSharedPreferences("blink-account",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    private void session(JSONObject value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] bytes=cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        String sealed=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(bytes,Base64.NO_WRAP);
        if(!prefs().edit().putString("sealed",sealed).commit())throw new IOException();
    }
    private JSONObject session() throws Exception {
        String value=prefs().getString("sealed",null);
        if(value==null)return new JSONObject();
        String[] parts=value.split("\\.",2);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    private String device() {
        String id=prefs().getString("device",null);
        if(id==null){id=java.util.UUID.randomUUID().toString();prefs().edit().putString("device",id).apply();}
        return id;
    }
    private static byte[] read(InputStream in,int limit) throws IOException {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=stream.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("too_large");out.write(buffer,0,n);}
            return out.toByteArray();
        }
    }
    @PluginMethod public void info(PluginCall call){io.execute(()->{try{
        JSONObject s=session();s.remove("token");JSObject result=new JSObject(s.toString());
        result.put("device",device());result.put("enabled",stage());
        result.put("version",getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),0).versionCode);
        call.resolve(result);
    }catch(Exception e){call.reject("凭证不可用");}});}
    @PluginMethod public void request(PluginCall call){io.execute(()->{
        HttpURLConnection c=null;
        try{
            String route=call.getString("route","");
            if(!stage()||!ROUTES.contains(route)){call.reject("内测服务不可用");return;}
            JSONObject s=session(),body=call.getObject("body",new JSObject());
            boolean login=route.equals("v1/login")||route.equals("v1/register");
            if(login){body.put("device",device());body.put("label",Build.MODEL);}
            if(!login&&!route.equals("v2/reset")&&!s.optString("account").equals(call.getString("account",""))){call.reject("账号已切换");return;}
            c=(HttpURLConnection)new URL("http://127.0.0.1:18765/"+route).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(4000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            if(!login&&!route.equals("v2/reset"))c.setRequestProperty("Authorization","Bearer "+s.optString("token"));
            try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int status=c.getResponseCode();
            JSONObject json=new JSONObject(new String(read(status>=400?c.getErrorStream():c.getInputStream(),4*1024*1024),StandardCharsets.UTF_8));
            if(status>=400){call.reject(json.optString("error","request_failed"),Integer.toString(status));return;}
            if(login){
                session(new JSONObject().put("account",json.getString("account")).put("name",json.getString("name")).put("token",json.getString("token")));
                json.remove("token");
            }else if(route.equals("v1/logout")||route.equals("v2/delete")){
                prefs().edit().remove("sealed").commit();
            }
            call.resolve(new JSObject(json.toString()));
        }catch(Exception e){call.reject("无法连接内网服务");}finally{if(c!=null)c.disconnect();}
    });}
    @PluginMethod public void forget(PluginCall call){io.execute(()->{prefs().edit().remove("sealed").commit();call.resolve();});}
    @PluginMethod public void exportFile(PluginCall call){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE,"Blink-backup.json");startActivityForResult(call,i,"savedFile");
    }
    @ActivityCallback private void savedFile(PluginCall call, ActivityResult result){
        if(call==null)return;
        if(result.getResultCode()!=android.app.Activity.RESULT_OK||result.getData()==null){call.reject("已取消");return;}
        try(OutputStream out=getContext().getContentResolver().openOutputStream(result.getData().getData(),"wt")){
            out.write(call.getString("content","").getBytes(StandardCharsets.UTF_8));call.resolve();
        }catch(Exception e){call.reject("导出失败");}
    }
    @PluginMethod public void importFile(PluginCall call){
        startActivityForResult(call,new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),"openedFile");
    }
    @ActivityCallback private void openedFile(PluginCall call, ActivityResult result){
        if(call==null)return;
        if(result.getResultCode()!=android.app.Activity.RESULT_OK||result.getData()==null){call.reject("已取消");return;}
        io.execute(()->{try{
            byte[] bytes=read(getContext().getContentResolver().openInputStream(result.getData().getData()),8*1024*1024);
            call.resolve(new JSObject().put("content",new String(bytes,StandardCharsets.UTF_8)));
        }catch(Exception e){call.reject("导入失败");}});
    }

    private HttpURLConnection download(String url) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(30000);return c;
    }
    private JSONObject release() throws Exception {
        HttpURLConnection c=download(RELEASE+"android-stage-channel/android-stage.json");
        try{
            JSONObject info=new JSONObject(new String(read(c.getInputStream(),8192),StandardCharsets.UTF_8));
            int v=info.getInt("version");
            if(v<1||!info.getString("url").equals(RELEASE+"android-stage-"+v+"/Blink-Android-stage.apk")||
               !info.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException();
            return info;
        }finally{c.disconnect();}
    }
    @PluginMethod public void update(PluginCall call){io.execute(()->{
        try{
            if(!stage()){call.reject("此渠道未开放");return;}
            JSONObject info=release();int version=info.getInt("version");
            int installed=getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),0).versionCode;
            if(!call.getBoolean("install",false)||installed>=version){call.resolve(new JSObject(info.toString()).put("installed",installed));return;}
            if(Build.VERSION.SDK_INT>=26&&!getContext().getPackageManager().canRequestPackageInstalls()){
                getActivity().runOnUiThread(()->getActivity().startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getContext().getPackageName()))));
                call.reject("请允许安装内测更新后再点安装");return;
            }
            File file=new File(getContext().getCacheDir(),"blink-update.apk");
            HttpURLConnection c=download(info.getString("url"));MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream input=c.getInputStream();OutputStream out=new FileOutputStream(file)){
                byte[] buf=new byte[32768];int n,total=0;
                while((n=input.read(buf))!=-1){total+=n;if(total>64*1024*1024)throw new IOException();hash.update(buf,0,n);out.write(buf,0,n);}
            }finally{c.disconnect();}
            StringBuilder hex=new StringBuilder();for(byte x:hash.digest())hex.append(String.format("%02x",x));
            PackageInfo apk=getContext().getPackageManager().getPackageArchiveInfo(file.getPath(),android.content.pm.PackageManager.GET_SIGNATURES);
            PackageInfo own=getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),android.content.pm.PackageManager.GET_SIGNATURES);
            if(!hex.toString().equals(info.getString("sha256"))||apk==null||!own.packageName.equals(apk.packageName)||apk.versionCode!=version||!Arrays.equals(own.signatures,apk.signatures))throw new IOException();
            Uri uri=FileProvider.getUriForFile(getContext(),own.packageName+".fileprovider",file);
            Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().runOnUiThread(()->getActivity().startActivity(i));call.resolve();
        }catch(Exception e){call.reject("更新检查或校验失败，请稍后重试");}
    });}
}
