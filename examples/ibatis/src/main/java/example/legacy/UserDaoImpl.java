package example.legacy;
import com.ibatis.sqlmap.client.SqlMapClient;
import java.sql.SQLException;
import java.util.List;
public class UserDaoImpl implements UserDao {
    private final SqlMapClient client;
    public UserDaoImpl(SqlMapClient client){this.client=client;}
    @Override public User getUser(Long id)throws SQLException{return (User)client.queryForObject("legacy.Users.load",id);}
    @Override @SuppressWarnings("unchecked") public List<User> search(User filter)throws SQLException{return client.queryForList("legacy.Users.search",filter);}
    @Override @SuppressWarnings("unchecked") public List<User> findByIds(List<Long> ids)throws SQLException{return client.queryForList("legacy.Users.findByIds",java.util.Collections.singletonMap("ids",ids));}
}
