package com.ryjs.reflection.api.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class ArrayUtil {
    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);
        int i = scanner.nextInt();
        List array = new ArrayList<Integer>();
        for (int m = 0;m<=i;m++) {

            int i2 = scanner.nextInt();
            array.add(i);
        }
    }
}
