package example.mapper;

import example.model.User;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    User findById(@Param("id") Long id);
    List<User> search(User filter);
    List<User> findByIds(@Param("ids") List<Long> ids);
}
