package ai.chat2db.server.domain.repository.mapper;

import ai.chat2db.server.domain.repository.entity.DataSourceDO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;

/**
 * Data Source Custom Mapper
 *
 * @author Jiaju Zhuang
 */
public interface DataSourceCustomMapper {

    IPage<DataSourceDO> selectPageWithPermission(IPage<DataSourceDO> page, @Param("admin") Boolean admin, @Param("userId") Long userId,
        @Param("searchKey")  String searchKey);
}
