class Solution {
public:
    int minOperations(string s) {
        int n = size(s);
        int cost = INT_MAX;
        for(int i = 0 ; i < n ; i++){
            int l = i, r = (n-1+i);
            r -= ((r >= n)?n:0); 
            int cnt = 0;
            int cur = 0;
            while(cnt < n/2){
                if(s[l] != s[r]){
                    int diff = abs(s[l]-s[r]);
                    // cout<<diff<<" ";
                    cur += min(26-diff,diff);
                }
                l++;
                l -= ((l >= n)?n:0);
                r--;
                r += ((r < 0)?n:0);
                cnt++;
            }
            cost = min(cost,cur+i);
        }
        return cost;
    }
};