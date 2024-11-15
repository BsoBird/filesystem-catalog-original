package demo.fscatalog.io.util;

import com.github.yitter.contract.IdGeneratorOptions;
import com.github.yitter.idgen.YitIdHelper;

import java.util.UUID;

public class UniIdUtils {
    public static final String UU_ID = "UUID";
    public static final String NANO_ID = "NANOID";
    public static final String SNOW_FLAKE = "SNOW_FLAKE";

    static {
        IdGeneratorOptions options = new IdGeneratorOptions((short)(System.currentTimeMillis()%Short.MAX_VALUE));
        YitIdHelper.setIdGenerator(options);
    }

    public static String getUniId(String type){
        if(UU_ID.equals(type)){
            return UUID.randomUUID().toString();
        }else if(SNOW_FLAKE.equals(type)){
            return String.valueOf(YitIdHelper.nextId());
        }else if(NANO_ID.equals(type)){
            return NanoIdUtils.randomNanoId();
        }
        throw new UnsupportedOperationException();
    }

    public static String getUniId(){
        return getUniId(UU_ID);
    }
}
