package com.ryjs.reflection.api.client.shader;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

public interface ICCUniform {
    default void glUniform1i(int i0) {
        glUniformI(i0);
    }

    default void glUniform2i(int i0, int i1) {
        glUniformI(i0, i1);
    }

    default void glUniform3i(int i0, int i1, int i2) {
        glUniformI(i0, i1, i2);
    }

    default void glUniform4i(int i0, int i1, int i2, int i3) {
        glUniformI(i0, i1, i2, i3);
    }

    default void glUniform1f(float f0) {
        glUniformF(false, f0);
    }

    default void glUniform2f(float f0, float f1) {
        glUniformF(false, f0, f1);
    }

    default void glUniform3f(float f0, float f1, float f2) {
        glUniformF(false, f0, f1, f2);
    }

    default void glUniform4f(float f0, float f1, float f2, float f3) {
        glUniformF(false, f0, f1, f2, f3);
    }

    default void glUniformMatrix3f(Matrix3f matrix) {
        glUniformF(false, toArrayF(matrix));
    }

    default void glUniformMatrix4f(Matrix4f matrix) {
        glUniformF(false, toArrayF(matrix));
    }

    void glUniformI(int... values);

    void glUniformF(boolean transpose, float... values);

    void glUniformD(boolean transpose, double... values);

    private static float[] toArrayF(Matrix3f matrix) {
        return new float[]{matrix.m00, matrix.m01, matrix.m02, matrix.m10, matrix.m11, matrix.m12, matrix.m20, matrix.m21, matrix.m22,};
    }

    private static float[] toArrayF(Matrix4f matrix) {
        return new float[]{matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(), matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(), matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(), matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33(),};
    }
}