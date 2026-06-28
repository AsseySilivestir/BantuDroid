# bench.b — Simple benchmarks for BantuDroid
# Tests basic operations to verify the engine works on Android.

print("=== Bantu Benchmark on Android ===");
print("");

# 1. Loop performance
$iterations = 10000;
$start = sua.time.now();

$count = 0;
while ($count < $iterations) {
    $count = $count + 1;
}

$end = sua.time.now();
$loop_time = $end - $start;
print("Loop (" + $iterations + " iterations): " + $loop_time + "ms");

# 2. String operations
$start = sua.time.now();
$result = "";
$count = 0;
while ($count < 1000) {
    $result = $result + "a";
    $count = $count + 1;
}
$end = sua.time.now();
print("String concat (1000x): " + ($end - $start) + "ms");

# 3. Function calls
$start = sua.time.now();
$count = 0;
while ($count < 5000) {
    def noop() { return 0; }
    noop();
    $count = $count + 1;
}
$end = sua.time.now();
print("Function calls (5000x): " + ($end - $start) + "ms");

# 4. Math operations
$start = sua.time.now();
$count = 0;
$sum = 0;
while ($count < 10000) {
    $sum = $sum + $count * 3.14 / 2.0;
    $count = $count + 1;
}
$end = sua.time.now();
print("Math ops (10000x): " + ($end - $start) + "ms");
print("  Result: " + $sum);

# 5. Array/List operations
$start = sua.time.now();
$list = [];
$count = 0;
while ($count < 1000) {
    $list.push($count);
    $count = $count + 1;
}
$end = sua.time.now();
print("Array push (1000x): " + ($end - $start) + "ms");

print("");
print("=== Benchmark Complete ===");
print("Your phone can run Bantu!");
