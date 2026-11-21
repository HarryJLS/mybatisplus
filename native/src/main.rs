use std::{io::{self, BufRead}, time::Instant};
fn main() {
    if std::env::args().any(|a|a=="--bench") {
        let ddl="CREATE TABLE users (id bigint primary key, name varchar(200), amount decimal(12,2), created_at timestamp)";
        let log="Preparing: select * from users where id=? and name=?\nParameters: 42(Long), O'Reilly(String)";
        for _ in 0..1000 {std::hint::black_box(mybatis_engine::parse_ddl(ddl).unwrap());}
        for (name, count) in [("ddl",100_000),("logs",100_000)] {
            let start=Instant::now();
            for _ in 0..count {
                if name=="ddl" {std::hint::black_box(mybatis_engine::parse_ddl(ddl).unwrap());}
                else {std::hint::black_box(mybatis_engine::logs_to_sql(log).unwrap());}
            }
            println!("{name}: {count} iterations, {:.3} us/op",start.elapsed().as_secs_f64()*1_000_000.0/count as f64);
        } return;
    }
    for line in io::stdin().lock().lines() {
        match line {Ok(line)=>println!("{}",mybatis_engine::process(&line)),Err(_)=>break}
    }
}
