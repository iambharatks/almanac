class Solution {
public:
    bool isInvalid(vector<int> &freq, int x){
        for(int i = 1; i <= 500; i++){
            if(freq[i] == 0) continue;
            int val1 = x+i;
            int val2 = x-i;
            if(val2 >= 0 && val2 <= 500){
                if(val2 == i && freq[val2] >= 2) return true;
                if(val2 != i && freq[val2] >= 1) return true;
            }
            if( val1 <= 500){
                if(freq[val1] >= 1) return true;
            }
        }
        return false;
    }
    int maxSubarray(vector<int>& nums) {
        int l = 0, r= 0;
        int n = size(nums);
        vector<int> freq(501);
        int ans = 0;
        while(r < n){
            while(l < r && isInvalid(freq,nums[r])){
                freq[nums[l++]]--;
            }
            freq[nums[r++]]++;
            ans = max(ans,r-l);
        }
        return ans;
    }
};