package example.legacy;
import java.sql.SQLException;
import java.util.List;
public interface UserDao {
    User getUser(Long id) throws SQLException;
    List<User> search(User filter) throws SQLException;
    List<User> findByIds(List<Long> ids) throws SQLException;
}
