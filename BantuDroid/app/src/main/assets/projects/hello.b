# hello.b — Hello World demo for BantuDroid
# This is the simplest Bantu program.

print("Mambo! Welcome to Bantu on Android!");
print("Your phone is now a Bantu runtime.");
print("");

$name = "BantuDroid User";
print("Hello, " + $name + "!");

# Function demo
def greet($person) {
    print("Habari, " + $person + "!");
}

greet("World");
greet("Android");

# Math demo
$pi = 3.14159;
$radius = 5;
$area = $pi * ($radius * $radius);
print("Area of circle (r=" + $radius + ") = " + $area);

print("");
print("Bantu is running on your phone!");
