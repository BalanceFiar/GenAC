package balance.genac.utils.math;

import java.util.Objects;


public class Vector3d {

    public double x;
    public double y;
    public double z;


    public Vector3d() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }


    public Vector3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }


    public Vector3d add(Vector3d other) {
        return new Vector3d(this.x + other.x, this.y + other.y, this.z + other.z);
    }


    public Vector3d subtract(Vector3d other) {
        return new Vector3d(this.x - other.x, this.y - other.y, this.z - other.z);
    }


    public Vector3d multiply(double scalar) {
        return new Vector3d(this.x * scalar, this.y * scalar, this.z * scalar);
    }


    public double dot(Vector3d other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }


    public Vector3d cross(Vector3d other) {
        double newX = this.y * other.z - this.z * other.y;
        double newY = this.z * other.x - this.x * other.z;
        double newZ = this.x * other.y - this.y * other.x;
        return new Vector3d(newX, newY, newZ);
    }


    public double length() {
        return Math.sqrt(lengthSquared());
    }


    public double lengthSquared() {
        return this.x * this.x + this.y * this.y + this.z * this.z;
    }


    public Vector3d normalize() {
        double len = length();
        if (len == 0) {
            return new Vector3d(0, 0, 0);
        }
        return new Vector3d(this.x / len, this.y / len, this.z / len);
    }


    public double distance(Vector3d other) {
        return Math.sqrt(distanceSquared(other));
    }


    public double distanceSquared(Vector3d other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public Vector3d clone() {
        return new Vector3d(this.x, this.y, this.z);
    }

    @Override
    public String toString() {
        return "Vector3d{" +
                "x=" + x +
                ", y=" + y +
                ", z=" + z +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vector3d vector3d = (Vector3d) o;
        return Double.compare(vector3d.x, x) == 0 &&
                Double.compare(vector3d.y, y) == 0 &&
                Double.compare(vector3d.z, z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }
}