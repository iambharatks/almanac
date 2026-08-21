class Solution {
public:
    long long findKthSmallest(vector<int>& coins, int k) {
        sort(begin(coins),end(coins));
        int n = size(coins);
        int totSubsets = 1<<n;
        vector<long long> lcm(totSubsets);
        for(int i = 0 ; i < totSubsets ; i++){
            int k = 0;
            int cur = i;
            long long curLcm = 1;
            int setCount = 0;
            while(cur){
                if(cur&1){
                    setCount ++;
                    curLcm = curLcm*coins[k]/gcd(curLcm,coins[k]);
                }
                cur >>= 1;
                k++;
            }
            lcm[i] = (setCount&1)?curLcm:-curLcm;
        }
        long long l = 1, r = 1ll*coins[0]*k;
        long long ans = 0;
        while(l <= r){
            long long c = l + (r-l)/2;
            long long tot = 0;
            for(int i = 1 ; i < totSubsets ; i++){
                long long curLcm = lcm[i];
                if(curLcm < 0) curLcm *= -1;
                if(curLcm > c) continue;
                tot += c/lcm[i];
            }
            if(tot >= k){
                ans = c;
                r = c-1;
            }else{
                l = c+1;
            }
        }
        return ans;
    }
};