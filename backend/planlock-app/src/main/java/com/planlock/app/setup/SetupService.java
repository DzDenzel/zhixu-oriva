package com.planlock.app.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.planlock.account.service.AuthService;
import com.planlock.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class SetupService {
    private final JdbcTemplate jdbc;
    private final AuthService accounts;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String token;
    private final String pluginsOverride;

    public SetupService(JdbcTemplate jdbc, AuthService accounts, ObjectMapper json,
            @Value("${planlock.deployment.enabled:false}") boolean enabled,
            @Value("${planlock.deployment.setup-token:}") String token,
            @Value("${planlock.deployment.public-plugins-catalog-url:}") String pluginsOverride) {
        this.jdbc=jdbc; this.accounts=accounts; this.json=json; this.enabled=enabled;
        this.token=token; this.pluginsOverride=pluginsOverride;
        if (enabled && token.isBlank()) throw new IllegalStateException("部署模式需要配置初始化口令");
    }

    public Map<String,Object> state() {
        return jdbc.queryForMap("SELECT instance_id, initialized FROM deployment_instance WHERE id = 1");
    }
    public boolean initialized(Map<String,Object> state) {
        Object value=state.get("initialized");
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()!=0;
    }
    public void requirePage() {
        requireEnabled();
        if (initialized(state())) throw failure(HttpStatus.GONE,"实例已初始化，请在 App 中连接并登录");
    }
    private void requireEnabled() {
        if (!enabled) throw failure(HttpStatus.NOT_FOUND,"此入口未启用");
    }

    public record SetupRequest(String setupToken,String instanceLabel,String account,String password,String accessUrl) {}
    public record SetupResult(Map<String,Object> connection,String qrDataUrl) {}

    @Transactional
    public SetupResult initialize(SetupRequest request) {
        requireEnabled();
        // The lock and account write share the Spring datasource transaction. A failed commit rolls both back.
        var current=jdbc.queryForMap("SELECT instance_id, initialized FROM deployment_instance WHERE id = 1 FOR UPDATE");
        if (initialized(current)) throw failure(HttpStatus.CONFLICT,"实例已初始化，初始化口令已失效");
        if (request.setupToken()==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), request.setupToken().getBytes(StandardCharsets.UTF_8)))
            throw failure(HttpStatus.FORBIDDEN,"初始化口令不正确");
        String label=trimAppWhitespace(request.instanceLabel());
        if (label.isEmpty() || label.length()>80 || label.chars().anyMatch(Character::isISOControl))
            throw failure(HttpStatus.BAD_REQUEST,"实例名称需要 1—80 个字符");
        String account=trimAppWhitespace(request.account());
        boolean email=account.length()<=100 && account.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
        boolean phone=account.length()<=20 && account.matches("\\+?[0-9]{3,19}");
        if (!email && !phone) throw failure(HttpStatus.BAD_REQUEST,"请填写有效邮箱或手机号");
        String password=request.password();
        if (password==null || password.isBlank() || password.length()<8 || password.getBytes(StandardCharsets.UTF_8).length>72 || password.chars().anyMatch(Character::isISOControl))
            throw failure(HttpStatus.BAD_REQUEST,"密码需至少 8 个字符且不超过 72 个 UTF-8 字节");
        var connection=connection(label,request.accessUrl(),pluginsOverride);
        String qr=qr(connection); // Generate before writes, so rendering failures never consume setup.
        Long owner;
        try { owner=accounts.createSetupOwner(account,password); }
        catch (BusinessException e) { throw failure(HttpStatus.BAD_REQUEST,"账号无法创建，请检查账号是否已存在"); }
        if (jdbc.update("UPDATE deployment_instance SET initialized = TRUE, instance_label = ?, owner_user_id = ?, initialized_at = CURRENT_TIMESTAMP WHERE id = 1 AND initialized = FALSE", label,owner)!=1)
            throw failure(HttpStatus.CONFLICT,"实例初始化状态已变化");
        return new SetupResult(connection,qr);
    }

    static Map<String,Object> connection(String label,String accessUrl,String override) {
        URI access=endpoint(accessUrl);
        String path=access.getRawPath();
        if (path!=null && !List.of("","/","/api/v1","/api/v1/").contains(path)) throw failure(HttpStatus.BAD_REQUEST,"访问地址请填写实例首页地址");
        String root=access.getScheme().toLowerCase(Locale.ROOT)+"://"+access.getRawAuthority();
        URI plugins=endpoint(override==null || override.isBlank() ? root+"/api/v1/plugins/catalog/" : override);
        boolean lan=isLanHost(access.getHost()) || isLanHost(plugins.getHost());
        var document=new LinkedHashMap<String,Object>();
        document.put("version",1); document.put("instanceLabel",label);
        document.put("apiBaseUrl",root+"/api/v1/");
        String prefix=plugins.toASCIIString();
        prefix=prefix.endsWith("/")?prefix:prefix+"/";
        if (prefix.length()>2048 || (root+"/api/v1/").length()>2048) throw failure(HttpStatus.BAD_REQUEST,"连接地址过长，请缩短地址后重试");
        document.put("pluginsCatalogUrl",prefix);
        document.put("lanAllowed",lan);
        return document;
    }
    /** Kotlin Char.isWhitespace (used by the App's trim) includes Unicode space characters. */
    private static String trimAppWhitespace(String value) {
        if (value==null) return "";
        int start=0;
        int end=value.length();
        while (start<end && isAppWhitespace(value.charAt(start))) start++;
        while (end>start && isAppWhitespace(value.charAt(end-1))) end--;
        return value.substring(start,end);
    }
    private static boolean isAppWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }
    private static URI endpoint(String raw) {
        try {
            if (raw==null || raw.isBlank() || raw.length()>2048 || raw.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)||c=='\\')) throw new IllegalArgumentException();
            URI uri=new URI(raw);
            if (uri.getHost()==null || uri.getHost().contains("%") || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535) throw new IllegalArgumentException();
            String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && isLanHost(uri.getHost()))) throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) { throw failure(HttpStatus.BAD_REQUEST,"访问地址无效：公网需 HTTPS，私网可使用 HTTP；地址不能包含凭据、查询或片段"); }
    }
    // Match the App's schema parser without resolving arbitrary DNS names.
    static boolean isLanHost(String input) {
        String host=input.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host=host.substring(1,host.length()-1);
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan") || (!host.contains(".") && !host.contains(":"))) return true;
        if (host.contains(":") || host.matches("[0-9.]+")) {
            try {
                InetAddress address=InetAddress.getByName(host); byte[] bytes=address.getAddress();
                return address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || bytes.length==16 && (bytes[0]&0xfe)==0xfc;
            } catch (Exception ignored) { return false; }
        }
        return false;
    }
    private String qr(Map<String,Object> document) {
        try {
            var bits=new QRCodeWriter().encode(json.writeValueAsString(document),BarcodeFormat.QR_CODE,420,420,Map.of(EncodeHintType.CHARACTER_SET,"UTF-8"));
            var image=new BufferedImage(bits.getWidth(),bits.getHeight(),BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<bits.getHeight();y++) for(int x=0;x<bits.getWidth();x++) image.setRGB(x,y,bits.get(x,y)?0x000000:0xffffff);
            var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"PNG",bytes);
            return "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ignored) { throw failure(HttpStatus.INTERNAL_SERVER_ERROR,"连接二维码生成失败，请重试"); }
    }
    private static ResponseStatusException failure(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
