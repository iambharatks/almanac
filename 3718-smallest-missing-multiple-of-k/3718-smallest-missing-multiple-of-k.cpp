class Solution {
public:
    int missingMultiple(vector<int>& nums, int k) {
        int n = size(nums);
        // maxVal = k*n, minVal = k;
        vector<bool> count(n);
        int ans = k;
        for(auto &i: nums){
            if(i%k == 0){
                if(i <= n*k && i%k == 0){ 
                    count[i/k] = true;
                    while(count[ans/k]) ans += k;
                }
            }
        }
        return ans;
    }
};