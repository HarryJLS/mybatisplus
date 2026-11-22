//! Standalone allocation probe; never linked into the plugin or normal engine benchmark.
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

struct CountingAllocator;
static COUNTING: AtomicBool = AtomicBool::new(false);
static CALLS: AtomicUsize = AtomicUsize::new(0);
static BYTES: AtomicUsize = AtomicUsize::new(0);
#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;

unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if COUNTING.load(Ordering::Relaxed) {
            CALLS.fetch_add(1, Ordering::Relaxed);
            BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        }
        System.alloc(layout)
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        if COUNTING.load(Ordering::Relaxed) {
            CALLS.fetch_add(1, Ordering::Relaxed);
            BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        }
        System.alloc_zeroed(layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        if COUNTING.load(Ordering::Relaxed) {
            CALLS.fetch_add(1, Ordering::Relaxed);
            BYTES.fetch_add(size, Ordering::Relaxed);
        }
        System.realloc(ptr, layout, size)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) { System.dealloc(ptr, layout); }
}

fn sample(name: &str, input: &str, count: usize, ddl: bool) {
    CALLS.store(0, Ordering::Relaxed);
    BYTES.store(0, Ordering::Relaxed);
    COUNTING.store(true, Ordering::Relaxed);
    for _ in 0..count {
        if ddl { std::hint::black_box(mybatis_engine::parse_ddl(input).unwrap()); }
        else { std::hint::black_box(mybatis_engine::logs_to_sql(input).unwrap()); }
    }
    COUNTING.store(false, Ordering::Relaxed);
    println!("{name}: {} allocation/reallocation calls/op, {} requested bytes/op",
        CALLS.load(Ordering::Relaxed) / count, BYTES.load(Ordering::Relaxed) / count);
}

fn main() {
    sample("DDL 4 columns", "CREATE TABLE users (id bigint primary key, name varchar(200), amount decimal(12,2), created_at timestamp)", 1000, true);
    let columns = (0..1000).map(|i| format!("c{i} varchar(100)")).collect::<Vec<_>>().join(",");
    sample("DDL 1000 columns", &format!("CREATE TABLE wide ({columns})"), 100, true);
    let log = "Preparing: select * from users where id=? and name=?\nParameters: 42(Long), O'Reilly(String)";
    sample("Logs 2 parameters", log, 1000, false);
    sample("Logs 100 pairs", &std::iter::repeat_n(log, 100).collect::<Vec<_>>().join("\n"), 100, false);
}
