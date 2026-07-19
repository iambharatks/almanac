class Solution {
public:
    int gcdOfOddEvenSums(int n) {
        // 1,3,5,7....n/2;
        // 2,4,6,...n/2;
        // n/2(2*a + (n-1)d)
        // sodd = n*n 
        // even = n*n+n
        // gcd(n*n+n,n*n) = n
        return n;
    }
};