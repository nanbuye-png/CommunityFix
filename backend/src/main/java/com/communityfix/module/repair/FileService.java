package com.communityfix.module.repair;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Service
public class FileService {
    private final Db db;private final OrderService orders;private final Clock clock;private final String mode,bucket,secret;private final Path directory;private final MinioClient minio;
    public FileService(Db db,OrderService orders,Clock clock,@Value("${communityfix.storage.mode}") String mode,@Value("${communityfix.storage.directory}") String directory,@Value("${communityfix.storage.endpoint}") String endpoint,@Value("${communityfix.storage.access-key}") String access,@Value("${communityfix.storage.secret-key}") String storageSecret,@Value("${communityfix.storage.bucket}") String bucket,@Value("${communityfix.jwt-secret}") String secret,@Value("${communityfix.demo-enabled}") boolean demo){
        this.db=db;this.orders=orders;this.clock=clock;this.mode=mode;this.directory=Path.of(directory).toAbsolutePath().normalize();this.bucket=bucket;this.secret=secret;
        if(!Set.of("local","minio").contains(mode))throw new IllegalStateException("Unsupported storage mode");
        if(mode.equals("local")&&!demo)throw new IllegalStateException("Local storage is restricted to DEMO_ENABLED=true");
        // Bound failed storage calls before the API proxy/client times out.
        this.minio=mode.equals("minio")?MinioClient.builder().endpoint(endpoint).credentials(access,storageSecret)
                .httpClient(new OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(2))
                    .readTimeout(java.time.Duration.ofSeconds(5)).writeTimeout(java.time.Duration.ofSeconds(5))
                    .callTimeout(java.time.Duration.ofSeconds(5)).retryOnConnectionFailure(false).build()).build():null;
    }
    public Object upload(MultipartFile file){
        if(file.isEmpty()||file.getSize()>5*1024*1024)throw ApiException.bad("图片大小须在5 MiB以内");
        byte[] bytes;String type;
        try{bytes=file.getBytes();try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw ApiException.bad("仅支持JPEG或PNG图片");var reader=readers.next();try{reader.setInput(input);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("jpeg","jpg","png").contains(format))throw ApiException.bad("仅支持JPEG或PNG图片");if((long)reader.getWidth(0)*reader.getHeight(0)>20_000_000)throw ApiException.bad("图片尺寸过大，请压缩后上传");reader.read(0);type=format.equals("png")?"image/png":"image/jpeg";}finally{reader.dispose();}}}catch(ApiException ex){throw ex;}catch(Exception ex){throw ApiException.bad("图片无法读取，请重新选择");}
        String key=UUID.randomUUID().toString()+ (type.equals("image/png")?".png":".jpg");
        try{put(key,bytes,type);long id=db.insert("file_asset",Db.fields("uploader_id",CurrentUser.get().id(),"object_key",key,"content_type",type,"size_bytes",bytes.length,"expires_at",clock.instant().plusSeconds(86400)));return Db.fields("attachmentId",id,"contentType",type,"sizeBytes",bytes.length);}catch(Exception ex){try{remove(key);}catch(Exception ignored){}if(ex instanceof ApiException api)throw api;throw new ApiException(503,"STORAGE_UNAVAILABLE","图片保存失败，请稍后重试");}
    }
    public Object access(long id,String base){
        var asset=db.required("SELECT * FROM file_asset WHERE id=?",id);var user=CurrentUser.get();
        var media=db.row("SELECT order_id FROM repair_order_media WHERE file_id=?",id);
        if(media!=null)orders.read(Db.id(media,"orderId"));else if(Db.id(asset,"uploaderId")!=user.id())throw ApiException.missing();
        if(!Set.of("TEMP","ATTACHED").contains(asset.get("status")))throw ApiException.missing();
        long expiry=clock.instant().getEpochSecond()+120;return Db.fields("url",base+"/api/files/"+id+"/content?expires="+expiry+"&signature="+sign(id+":"+expiry),"expiresAt",java.time.Instant.ofEpochSecond(expiry));
    }
    public Content content(long id,long expiry,String signature){
        if(expiry<clock.instant().getEpochSecond()||expiry>clock.instant().getEpochSecond()+180||!MessageDigest.isEqual(sign(id+":"+expiry).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8)))throw ApiException.forbidden();
        var asset=db.required("SELECT * FROM file_asset WHERE id=?",id);if(!Set.of("TEMP","ATTACHED").contains(asset.get("status")))throw ApiException.missing();
        try{return new Content(get((String)asset.get("objectKey")),(String)asset.get("contentType"));}catch(Exception ex){throw new ApiException(503,"STORAGE_UNAVAILABLE","图片暂时无法读取");}
    }
    private String sign(String text){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("file:"+text).getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Path path(String key){Path target=directory.resolve(key).normalize();if(!target.startsWith(directory)||!key.matches("[a-zA-Z0-9.-]+"))throw new IllegalStateException("Invalid object key");return target;}
    private synchronized void put(String key,byte[] bytes,String type)throws Exception{
        if(mode.equals("local")){Files.createDirectories(directory);Files.write(path(key),bytes,StandardOpenOption.CREATE_NEW);}
        else{ensureBucket(minio,bucket);minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(bytes),bytes.length,-1).contentType(type).build());}
    }
    static void ensureBucket(MinioClient minio, String bucket) throws Exception {
        if (minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) return;
        try { minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); }
        catch (ErrorResponseException ex) {
            // Another instance can create our bucket between HEAD and PUT. Do not
            // swallow access-denied or a bucket owned by a different account.
            if (!"BucketAlreadyOwnedByYou".equals(ex.errorResponse().code())) throw ex;
        }
    }
    private byte[] get(String key)throws Exception{if(mode.equals("local"))return Files.readAllBytes(path(key));try(var input=minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())){return input.readAllBytes();}}
    public void remove(String key)throws Exception{if(mode.equals("local"))Files.deleteIfExists(path(key));else minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}
    public record Content(byte[] bytes,String type){}
}
