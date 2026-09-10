class Solution {
public:
    long long countCommas(long long n) {
        long long p = 1000, res = 0;
        while(p <= n){
            res += n-p+1;
            p *= 1000;
        }
        // this doen't work as error due to calculations
        // long long t = log10(n)+1;
        // cout<<t;
        // t = (t-1)/3;
        // res = t*(n+1) - p*(pow(p,t)-1)/(p-1);
        return res;
    }
};